(ns pds.plc-recovery-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.net :as net]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as tls]
            [pds.plc-recovery :as recovery]
            [pds.plc-recovery-admin :as cli]
            [pds.plc-test :as ops]
            [pds.protocol.codec :as codec])
  (:import [java.time Instant]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(defn history
  ([] (history "ES256" (Instant/now)))
  ([algorithm ^Instant now]
   (let [high (crypto/keypair algorithm) mid (crypto/keypair algorithm) low (crypto/keypair "ES256K")
         genesis (plc/sign-operation (ops/unsigned [high mid low] low) low)
         did (plc/genesis-did genesis)
         child (ops/update-op genesis low {"alsoKnownAs" ["at://changed.example.com"]})
         descendant (ops/update-op child low {})
         operation (ops/update-op genesis mid {"alsoKnownAs" ["at://recovered.example.com"]})]
     {:did did :high high :mid mid :low low :genesis genesis :child child :descendant descendant :operation operation
      :entries [(ops/row did genesis (str (.minusSeconds now 120)) false)
                (ops/row did child (str (.minusSeconds now 60)) false)
                (ops/row did descendant (str (.minusSeconds now 30)) false)]})))
(defn reason [f] (:reason (tls/failure f)))
(defn submit [client origin {:keys [did entries operation]}]
  (recovery/submit! client origin did (get (peek entries) "cid") (plc/operation-cid operation) operation))

(deftest signed-recovery-preflight-verifies-priority-window-and-complete-audit
  (doseq [algorithm ["ES256" "ES256K"]]
    (let [now (Instant/parse "2026-09-27T00:00:00Z")
          {:keys [did mid low genesis child descendant entries operation]} (history algorithm now)
          plan (plc/recovery-plan! did entries operation now)
          expiry (.plusSeconds (.minusSeconds now 60) (* 72 3600))]
      (is (= [(plc/operation-cid child) (plc/operation-cid descendant)] (:nullifiedCids plan)))
      (is (= (plc/did-key mid) (:signer plan)))
      (is (= (str expiry) (:expiresAt plan)))
      (is (= (:operationCid plan) (:head (plc/verify-audit! did (:entries plan)))))
      (is (= (:nullifiedCids plan) (:nullifiedCids (plc/recovery-plan! did entries operation expiry))))
      (is (thrown? Exception (plc/recovery-plan! did entries operation (.plusMillis expiry 1))))
      (doseq [bad [(ops/update-op genesis low {}) ; same priority
                   (ops/update-op descendant mid {}) ; normal successor
                   (ops/update-op genesis (crypto/keypair algorithm) {}) ; unauthorized
                   (assoc operation "alsoKnownAs" ["at://tampered.example.com"])]]
        (is (thrown? Exception (plc/recovery-plan! did entries bad now))))
      (is (thrown? Exception (plc/recovery-plan! did (assoc-in entries [1 "nullified"] true) operation now)))
      (is (thrown? Exception (plc/recovery-plan! did entries operation (.minusSeconds now 30))))
      (is (thrown? Exception (plc/recovery-plan! did (:entries plan) (ops/update-op child mid {}) now))))))

(deftest recovery-can-replace-tombstones-and-publish-a-tombstone
  (let [now (Instant/now) {:keys [did genesis low mid entries operation]} (history "ES256K" now)
        tombstone (ops/tombstone genesis low)
        tombstone-log [(first entries) (ops/row did tombstone (str (.minusSeconds now 30)) false)]
        recovered (plc/recovery-plan! did tombstone-log operation now)
        deleted (plc/recovery-plan! did entries (ops/tombstone genesis mid) now)]
    (is (= [(plc/operation-cid tombstone)] (:nullifiedCids recovered)))
    (is (some? (:directoryData recovered)))
    (is (nil? (:directoryData deleted)))
    (is (nil? (:data (plc/verify-audit! did (:entries deleted)))))))

(deftest real-tls-recovery-is-confirmed-and-retries-never-republish-known-cids
  (tls/with-directory
    (fn [{:keys [client origin logs calls mode]}]
      (doseq [behavior [:accept :accept-error :accept-drop]]
        (let [{:keys [did entries operation high genesis mid] :as fixture} (history)]
          (swap! logs assoc did entries)
          (reset! mode behavior)
          (let [plan (recovery/inspect! client origin did operation)
                before (count (tls/posts calls)) result (submit client origin fixture)]
            (is (= "ready" (:state plan)))
            (is (= "confirmed" (:state result)))
            (is (= (set (:nullifiedCids plan)) (set (:auditNullifiedCids result))))
            (is (= result (submit client origin fixture)))
            (is (= (inc before) (count (tls/posts calls))))
            (reset! mode :accept)
            (let [next (ops/update-op operation mid {})]
              (directory/ensure-operation! client origin did next)
              (is (= "accepted" (:state (submit client origin fixture))))
              (is (= (+ before 2) (count (tls/posts calls)))))
            (let [stronger (ops/update-op genesis high {"alsoKnownAs" ["at://strongest.example.com"]})
                  current (get-in @logs [did (dec (count (get @logs did))) "cid"])]
              (is (= "confirmed" (:state (recovery/submit! client origin did current (plc/operation-cid stronger) stronger))))
              (is (= "superseded" (:state (submit client origin fixture))))
              (is (= (+ before 3) (count (tls/posts calls)))))))))))

(deftest recovery-refuses-stale-head-changed-file-invalid-audit-and-bad-authority-before-post
  (tls/with-directory
    (fn [{:keys [client origin logs calls audit-body]}]
      (let [{:keys [did entries genesis low operation] :as fixture} (history)]
        (swap! logs assoc did entries)
        (is (= :operation-mismatch (reason #(recovery/submit! client origin did (get (peek entries) "cid")
                                                                             (plc/operation-cid genesis) operation))))
        (is (= :head-mismatch (reason #(recovery/submit! client origin did (plc/operation-cid genesis)
                                                                        (plc/operation-cid operation) operation))))
        (is (= :invalid-recovery (reason #(submit client origin (assoc fixture :operation (ops/update-op genesis low {}))))))
        (is (= :head-mismatch (reason #(submit client origin (assoc fixture :entries [(first entries)])))))
        (reset! audit-body "[]")
        (is (= :invalid-audit (reason #(submit client origin fixture))))
        (is (empty? (tls/posts calls)))))))

(deftest retryable-failure-keeps-the-signed-operation-and-rejections-never-follow-redirects
  (tls/with-directory
    (fn [{:keys [client origin logs calls mode]}]
      (let [{:keys [did entries operation] :as fixture} (history)]
        (swap! logs assoc did entries)
        (doseq [[behavior expected retryable] [[:ignore :unconfirmed true] [:reject :rejected false] [:redirect :redirect false]]]
          (reset! mode behavior)
          (is (= {:type :plc-recovery :reason expected :retryable retryable}
                 (tls/failure #(submit client origin fixture))))
          (is (= entries (get @logs did))))
        (is (= 3 (count (tls/posts calls))))
        (reset! mode :accept)
        (is (= "confirmed" (:state (submit client origin fixture))))
        (is (= operation (get (peek (get @logs did)) "operation")))))))

(deftest plc-post-cannot-atomically-fence-new-descendants-but-confirms-actual-nullification
  (tls/with-directory
    (fn [{:keys [client origin logs]}]
      (let [{:keys [did entries low descendant] :as fixture} (history)
            next (ops/update-op descendant low {"alsoKnownAs" ["at://raced.example.com"]})
            post! net/post-json! raced? (atom false)]
        (swap! logs assoc did entries)
        (with-redefs [net/post-json! (fn [& args]
                                      (when (compare-and-set! raced? false true)
                                        (swap! logs update did conj (ops/row did next (str (.minusSeconds (Instant/now) 1)) false)))
                                      (apply post! args))]
          (let [result (submit client origin fixture)]
            (is (= "confirmed" (:state result)))
            (is (= 3 (count (:auditNullifiedCids result))))
            (is (some #{(plc/operation-cid next)} (:auditNullifiedCids result)))))))))

(deftest signed-file-cli-is-bounded-private-key-free-and-independent-of-pds-secrets
  (let [path (Files/createTempFile "pds-recovery-cli-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (tls/with-directory
        (fn [{:keys [client origin logs mode]}]
          (let [{:keys [did entries operation] :as fixture} (history)
                submit-args ["submit" did (get (peek entries) "cid") (plc/operation-cid operation) (str path)]
                execute! cli/execute!
                env {"PDS_PLC_URL" origin "PDS_DATABASE_URL" "not-a-database" "PDS_MASTER_KEY" "secret-not-needed"}]
            (swap! logs assoc did entries)
            (spit (str path) (json/write-str operation))
            (with-redefs [cli/execute! (fn [_ origin command op] (execute! client origin command op))]
              (is (= "ready" (get-in (cli/run! ["inspect" did (str path)] env) [:result :state])))
              (reset! mode :ignore)
              (is (= 2 (:exit (cli/run! submit-args env))))
              (reset! mode :reject)
              (is (= 1 (:exit (cli/run! submit-args env))))
              (reset! mode :accept)
              (is (= 0 (:exit (cli/run! submit-args env))))
              (is (= "confirmed" (get-in (cli/run! submit-args env) [:result :state]))))
            (doseq [bad [(assoc operation "privateKey" "secret-not-for-directory") {"operation" operation}
                         (dissoc operation "sig")]]
              (spit (str path) (json/write-str bad))
              (let [result (cli/run! submit-args env)]
                (is (= 1 (:exit result)))
                (is (= "invalid-operation-file" (get-in result [:result :reason])))
                (is (not (.contains (pr-str result) "secret-not")))))
            (doseq [bad [(apply str (repeat 65537 " ")) "{}{}" (str (apply str (repeat 65 "[")) "0" (apply str (repeat 65 "]")))]]
              (spit (str path) bad)
              (is (= "invalid-operation-file" (get-in (cli/run! submit-args env) [:result :reason]))))
            (doseq [args [[] ["submit" did] ["inspect" did (str path) "extra"]]]
              (is (= 64 (:exit (cli/run! args {})))))
            (is (= "invalid-input" (get-in (cli/run! ["inspect" "did:web:alice.example.com" (str path)] {}) [:result :reason]))))))
      (finally (Files/deleteIfExists path)))))

(deftest pinned-reference-library-agrees-with-recovery-plans
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [path (Files/createTempFile "pds-recovery-reference-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))
          now (Instant/parse "2026-09-27T00:00:00Z")
          fixtures (for [algorithm ["ES256" "ES256K"]
                         :let [{:keys [did entries operation low genesis]} (history algorithm now)]
                         [op time valid?] [[operation now true]
                                           [operation (.plusSeconds now (* 72 3600)) false]
                                           [(ops/update-op genesis low {}) now false]
                                           [(ops/tombstone genesis (crypto/keypair algorithm)) now false]]]
                     {:did did :entries entries :operation op :now (str time) :valid valid?
                      :nullified (when valid? (:nullifiedCids (plc/recovery-plan! did entries op time)))})]
      (try
        (spit (str path) (json/write-str fixtures))
        (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/verify-recovery-submission.mjs" (str path)]) (.redirectErrorStream true)))]
          (try
            (is (.waitFor process 30 TimeUnit/SECONDS))
            (when-not (.isAlive process) (is (zero? (.exitValue process)) (slurp (.getInputStream process))))
            (finally (when (.isAlive process) (.destroyForcibly process)))))
        (finally (Files/deleteIfExists path))))))

(deftest recovery-from-legacy-genesis-preserves-original-signed-history
  (let [high (crypto/keypair "ES256K") low (crypto/keypair "ES256") now (Instant/now)
        genesis (plc/sign-operation {"type" "create" "prev" nil "signingKey" (plc/did-key low)
                                     "recoveryKey" (plc/did-key high) "handle" "alice.example.com" "service" "pds.example.com"} low)
        did (plc/genesis-did genesis) child (ops/update-op genesis low {})
        op (ops/update-op genesis high {"alsoKnownAs" ["at://recovered.example.com"]})
        entries [(ops/row did genesis (str (.minusSeconds now 120)) false)
                 (ops/row did child (str (.minusSeconds now 60)) false)]
        result (plc/recovery-plan! did entries op now)]
    (is (= genesis (get-in result [:entries 0 "operation"])))
    (is (= [(plc/operation-cid child)] (:nullifiedCids result)))
    (is (= (plc/operation-cid op) (:head (plc/verify-audit! did (:entries result)))))))

(deftest successful-post-with-an-invalid-confirmation-audit-is-not-success
  (tls/with-directory
    (fn [{:keys [client origin logs calls audit-body]}]
      (let [{:keys [did entries] :as fixture} (history) post! net/post-json!]
        (swap! logs assoc did entries)
        (with-redefs [net/post-json! (fn [client url body]
                                      (let [result (post! client url body {})]
                                        (reset! audit-body "[]")
                                        result))]
          (is (= :invalid-audit (reason #(submit client origin fixture)))))
        (is (= 1 (count (tls/posts calls))))
        (reset! audit-body nil)
        (is (= "confirmed" (:state (submit client origin fixture))))
        (is (= 1 (count (tls/posts calls))))))))
