(ns pds.oauth-client-auth-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.oauth.client-auth :as auth]
            [pds.oauth.client-auth-test :as client]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof])
  (:import [java.time Instant]))

(use-fixtures :each fixture/isolated-database)
(defn rows [sql] (with-open [conn (db/connection fixture/*ds*)] (db/query conn sql)))
(defn scalar [sql] (:n (first (rows sql))))
(defn accept [resolved params] (auth/accept! fixture/*ds* resolved client/settings params))

(deftest assertion-replay-is-shared-across-keys-and-connections
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [key (client/new-key) other (client/new-key) resolved (client/resolved [key other])
          claims (client/claims) input (client/params key claims)
          gate (promise)
          tasks (mapv (fn [i] (future @gate
                               (try
                                 (accept resolved (if (even? i) input (update input "client_assertion" proof/other-s)))
                                 :accepted
                                 (catch clojure.lang.ExceptionInfo e (:oauth-error (ex-data e)))))) (range 12))]
      (deliver gate true)
      (let [results (mapv #(deref % 15000 :timeout) tasks)]
        (is (= 1 (count (filter #{:accepted} results))))
        (is (= 11 (count (filter #{"invalid_client"} results)))))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))
      (is (= [{:client_id_hash (crypto/digest-token metadata/client-id) :jti_hash (crypto/digest-token (get claims "jti"))}]
             (rows "SELECT client_id_hash, jti_hash FROM oauth_client_assertion_uses")))
      (is (= "invalid_client" (client/error #(accept resolved (client/params other claims))))
          "Publishing another key cannot reuse an accepted jti")
      (let [reopened (db/datasource {:url (.getURL fixture/*ds*) :user (.getUser fixture/*ds*) :password (.getPassword fixture/*ds*)})]
        (is (= "invalid_client" (client/error #(auth/accept! reopened resolved client/settings input)))))
      (with-open [conn (db/connection fixture/*ds*)]
        (is (thrown? Exception (auth/consume! conn (auth/verify! resolved client/settings input))))))))

(deftest invalid-credentials-cannot-spend-assertions-and-failed-grants-cannot-revive-them
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [key (client/new-key) resolved (client/resolved [key]) input (client/params key)
          candidate (auth/verify! resolved client/settings input)]
      (is (= "invalid_client" (client/error #(accept resolved (assoc input "client_assertion_type" "wrong")))))
      (is (= 0 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))
      (is (= "invalid_client" (client/error #(auth/accept! fixture/*ds* resolved client/settings input
                                                         (assoc (:binding candidate) :kid "wrong")))))
      (is (= 0 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))
      (db/transact! fixture/*ds* #(db/execute! % "CREATE TABLE oauth_grant_test (value text NOT NULL)"))
      (accept resolved input)
      (is (thrown? Exception
            (db/transact! fixture/*ds*
              (fn [conn]
                (db/execute! conn "INSERT INTO oauth_grant_test VALUES ('rolled back')")
                (throw (ex-info "Grant failed after authentication" {}))))))
      (is (= 0 (scalar "SELECT count(*) AS n FROM oauth_grant_test")))
      (is (= "invalid_client" (client/error #(accept resolved input))))
      (let [late (auth/verify! resolved client/settings (client/params key))]
        (with-redefs [dpop/now (constantly (:expires-at late))]
          (is (= "invalid_client" (client/error #(db/transact! fixture/*ds* (fn [conn] (auth/consume! conn late))))))))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses"))))))

(deftest ledger-isolates-client-ids-and-cleans-expired-records-in-batches
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [key (client/new-key) resolved (client/resolved [key]) claims (client/claims)
          other-id "https://another.example.com/client.json"
          other-client (-> resolved (assoc :client-id other-id) (assoc-in [:metadata "client_id"] other-id))
          other-input (assoc (client/params key (assoc claims "iss" other-id "sub" other-id)) "client_id" other-id)]
      (db/transact! fixture/*ds*
        #(db/execute! % "INSERT INTO oauth_client_assertion_uses SELECT ?, lpad(n::text, 43, '0'), ? FROM generate_series(1, 1005) AS n"
                      (crypto/token) (Instant/ofEpochSecond (dec proof/timestamp))))
      (accept resolved (client/params key claims))
      (is (= 6 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))
      (is (map? (accept other-client other-input)))
      (is (= 2 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))
      (with-redefs [dpop/now (constantly (+ proof/timestamp 120))]
        (is (= "invalid_client" (client/error #(accept other-client other-input))))
        (accept resolved (client/params key))
        (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))))))

(deftest nonce-retries-and-dual-proof-acceptance-are-atomic
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [key (client/new-key) resolved (client/resolved [key]) first-assertion (client/params key)
          next-assertion (client/params key) dpop-key (crypto/keypair)
          first-proof (proof/sign dpop-key (proof/claims)) next-proof (proof/sign dpop-key (proof/claims))
          authenticate (fn [assertion token]
                         (auth/accept-request! fixture/*ds* resolved proof/settings assertion token proof/context nil))]
      (is (= "use_dpop_nonce"
             (proof/error #(authenticate first-assertion (proof/sign dpop-key (dissoc (proof/claims) "nonce"))))))
      (is (= 0 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))
      (is (= 0 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))
      (let [accepted (authenticate first-assertion first-proof)]
        (is (= (:binding (auth/verify! resolved client/settings first-assertion)) (:client-binding accepted)))
        (is (= (:jkt (dpop/verify! proof/settings first-proof proof/context)) (:dpop-jkt accepted))))
      (is (= "invalid_dpop_proof" (proof/error #(authenticate next-assertion first-proof))))
      (is (= "invalid_client" (proof/error #(authenticate first-assertion next-proof))))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))
      (is (map? (authenticate next-assertion next-proof)) "Rejected combinations did not partially spend credentials")
      (is (= 2 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))
      (is (= 2 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))
      (let [public-client (metadata/resolve-value (metadata/metadata))]
        (is (= "none" (get-in (auth/accept-request! fixture/*ds* public-client proof/settings
                                                   {"client_id" metadata/client-id} (proof/sign dpop-key (proof/claims))
                                                   proof/context nil) [:client-binding :method])))
        (is (= 3 (scalar "SELECT count(*) AS n FROM oauth_dpop_uses")))
        (is (= 2 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))))))
