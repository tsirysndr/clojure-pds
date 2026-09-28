(ns pds.oauth-dpop-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof]
            [pds.oauth.proof-store :as store])
  (:import [java.nio.file Files]
           [java.time Instant]
           [java.util.concurrent TimeUnit]))

(use-fixtures :each fixture/isolated-database)
(defn rows [sql] (with-open [conn (db/connection fixture/*ds*)] (db/query conn sql)))
(defn scalar [sql] (:n (first (rows sql))))
(defn accept [token] (store/accept! fixture/*ds* proof/settings token proof/context))

(deftest concurrent-and-resigned-proofs-are-consumed-once
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [key (crypto/keypair) payload (proof/claims) token (proof/sign key payload)
          gate (promise)
          tasks (mapv (fn [i] (future @gate
                               (try (accept (if (even? i) token (proof/other-s token))) :used
                                    (catch clojure.lang.ExceptionInfo e (:oauth-error (ex-data e)))))) (range 12))]
      (deliver gate true)
      (let [results (mapv #(deref % 15000 :timeout) tasks)]
        (is (= 1 (count (filter #{:used} results))))
        (is (= 11 (count (filter #{"invalid_dpop_proof"} results)))))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))
      (is (= [(:jti-hash (dpop/verify! proof/settings token proof/context))]
             (mapv :jti_hash (rows "SELECT jti_hash FROM oauth_dpop_uses"))))
      ;; Re-signing for a different endpoint with the same jti is still replay.
      (let [context (assoc proof/context :url "https://pds.example.com/oauth/par")]
        (is (= "invalid_dpop_proof"
               (proof/error #(store/accept! fixture/*ds* proof/settings
                                            (proof/sign key (assoc payload "htu" (:url context))) context)))))
      (with-redefs [dpop/now (constantly (+ proof/timestamp 120))]
        (is (= "invalid_dpop_proof"
               (proof/error #(accept (proof/sign key (assoc payload "iat" (dpop/now) "nonce" (dpop/nonce proof/settings))))))
            "A nonce rotation does not reset the replay ledger"))
      ;; The nonce identity is scoped to the signing key, not a global jti string.
      (is (map? (accept (proof/sign (crypto/keypair) payload))))
      (is (= 2 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))
      (with-open [conn (db/connection fixture/*ds*)]
        (is (thrown? Exception (store/consume! conn (dpop/verify! proof/settings token proof/context)))
            "Untransactional insertion must not silently succeed")))))

(deftest rejected-proofs-and-business-rollbacks-do-not-revive-accepted-proofs
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [key (crypto/keypair) payload (proof/claims) token (proof/sign key payload)]
      (is (= "use_dpop_nonce" (proof/error #(accept (proof/sign key (dissoc payload "nonce"))))))
      (is (= 0 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))
      (is (= "invalid_dpop_proof" (proof/error #(accept (proof/sign key (assoc payload "htm" "GET"))))))
      (is (= 0 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))
      (db/transact! fixture/*ds* #(db/execute! % "CREATE TABLE oauth_test_effects (value text NOT NULL)"))
      (accept token)
      (is (thrown? Exception
            (db/transact! fixture/*ds*
              (fn [conn]
                (db/execute! conn "INSERT INTO oauth_test_effects VALUES ('rolled back')")
                (throw (ex-info "Application failure after proof acceptance" {}))))))
      (is (= 0 (scalar "SELECT count(*) AS n FROM oauth_test_effects")))
      (is (= "invalid_dpop_proof" (proof/error #(accept token))))
      (let [candidate (dpop/verify! proof/settings (proof/sign key (proof/claims)) proof/context)]
        (with-redefs [dpop/now (constantly (:expires-at candidate))]
          (is (= "invalid_dpop_proof"
                 (proof/error #(db/transact! fixture/*ds* (fn [conn] (store/consume! conn candidate))))))))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses"))))))

(deftest bounded-expiry-cleanup-preserves-live-replay-records
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [key (crypto/keypair) token (proof/sign key (proof/claims))]
      (db/transact! fixture/*ds*
        #(db/execute! % "INSERT INTO oauth_dpop_uses SELECT ?, lpad(n::text, 43, '0'), ? FROM generate_series(1, 1005) AS n"
                      (crypto/token) (Instant/ofEpochSecond (dec proof/timestamp))))
      (accept token)
      (is (= 6 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))
      (is (= "invalid_dpop_proof" (proof/error #(accept token))))
      (accept (proof/sign key (proof/claims)))
      (is (= 2 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))
      (with-redefs [dpop/now (constantly (+ proof/timestamp 300))]
        (accept (proof/sign key (proof/claims)))
        (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))))))

(deftest a-fresh-jvm-rejects-a-proof-consumed-by-another-process
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [token (proof/sign (crypto/keypair) (proof/claims))
          path (Files/createTempFile "pds-dpop-restart-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (accept token)
        (spit (str path) (json/write-str {:token token :now proof/timestamp}))
        (let [code (str "(require '[clojure.data.json :as json] '[pds.db :as db] '[pds.oauth.dpop :as dpop] "
                        "'[pds.oauth.dpop-test :as proof] '[pds.oauth.proof-store :as store]) "
                        "(let [input (json/read-str (slurp (System/getenv \"PDS_PROOF_FIXTURE\"))) ds (db/datasource (db/settings))] "
                        "(with-redefs [dpop/now (constantly (get input \"now\"))] "
                        "(System/exit (if (= \"invalid_dpop_proof\" (proof/error #(store/accept! ds proof/settings (get input \"token\") proof/context))) 0 1))))")
              builder (ProcessBuilder. [(str (System/getProperty "java.home") "/bin/java") "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" code])]
          (.putAll (.environment builder) (merge (fixture/database-env) {"PDS_PROOF_FIXTURE" (str path)}))
          (.redirectErrorStream builder true)
          (let [process (.start builder) done? (.waitFor process 30 TimeUnit/SECONDS)]
            (when-not done? (.destroyForcibly process))
            (is done?)
            (when done? (is (= 0 (.exitValue process)) (slurp (.getInputStream process))))))
        (finally (Files/deleteIfExists path))))))
