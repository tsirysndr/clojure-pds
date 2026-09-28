(ns pds.master-keys-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.app-passwords :as passwords]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.handles :as handles]
            [pds.http :as http]
            [pds.master-key-admin :as cli]
            [pds.master-keys :as keys]
            [pds.oauth-tokens-test :as oauth]
            [pds.oauth-resource-test :as resource]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof]
            [pds.oauth.tokens :as tokens]
            [pds.plc-directory-test :as tls]
            [pds.plc-keys :as control]
            [pds.plc-keys-test :as control-test]
            [pds.plc-provision-test :as provision]
            [pds.protocol.repository :as repository]
            [pds.repo :as repo]
            [pds.reserved-keys :as reserved]
            [pds.security.factors :as factors]
            [pds.security.totp :as totp]
            [pds.server-api-test :as api]
            [pds.signing-keys :as signing])
  (:import [java.net.http HttpClient]
           [java.util.concurrent TimeUnit]))
(use-fixtures :each fixture/isolated-database)
(defn tx [f] (db/transact! fixture/*ds* f))
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:master-key-error (ex-data e)))))
(defn env [] (fixture/database-env))
(defn secrets []
  (into {}
        (for [{:keys [table column]} keys/columns]
          [[table column] (mapv #(update % :sealed vec)
                               (provision/rows (str "SELECT did, " column " AS sealed FROM " table " WHERE " column " IS NOT NULL ORDER BY did")))])))
(defn register! [key] (with-open [_ (keys/open-lease! fixture/*ds* key)] (keys/status! fixture/*ds*)))
(defn rewrap [old new] (keys/rewrap! fixture/*ds* old new (keys/fingerprint old)))
(defn child [args overrides]
  (let [builder (ProcessBuilder. ^java.util.List (into ["mise" "exec" "--" "clojure" "-M:master-key"] args))
        environment (.environment builder)]
    (.remove environment "PDS_MASTER_KEY") (.remove environment "PDS_NEW_MASTER_KEY")
    (.putAll environment (merge (env) overrides))
    (let [process (.start builder) output (future (slurp (.getInputStream process))) errors (future (slurp (.getErrorStream process)))]
      (try
        (is (.waitFor process 45 TimeUnit/SECONDS))
        (when-not (.isAlive process)
          {:exit (.exitValue process) :result (json/read-str (deref output 5000 "{}") :key-fn keyword)})
        (finally (when (.isAlive process) (.destroyForcibly process))
                 (deref output 5000 nil) (deref errors 5000 nil))))))

(deftest rewrapping-preserves-keys-factors-and-pending-operations
  (tls/with-directory
    (fn [{:keys [client origin mode]}]
      (let [settings (provision/settings client origin) old (:master-key settings) new (crypto/random-bytes 32)
            updated (merge settings (auth/settings {"PDS_MASTER_KEY" (crypto/b64 new)}))
            alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            bob (accounts/create! fixture/*ds* settings (assoc (provision/signup) "handle" "bob.example.com" "email" "bob@example.com"))
            web (accounts/create! fixture/*ds* (assoc settings :did-method :web) (assoc (provision/signup) "handle" "web.example.com" "email" "web@example.com"))]
        (tx #(repo/apply-writes! % settings did [{:action :create :collection "com.example.note" :rkey "one" :value {"$type" "com.example.note"}}] nil))
        (tx #(factors/begin! % settings did))
        (let [secret (crypto/unseal old (str "pds/totp/v1/" did) (:sealed_secret (first (provision/rows "SELECT * FROM account_totp WHERE did = ?" did))))
              confirmed (tx #(factors/confirm! % settings did (totp/code secret (quot (factors/now) 30))))
              primary (accounts/login! fixture/*ds* settings {"identifier" "alice.example.com" "password" "signup-password" "authFactorToken" (first (:recovery-codes confirmed))})
              app-password (tx #(passwords/create! % settings (assoc primary :access-scope "com.atproto.access") {"name" "legacy"}))
              before (provision/rows "SELECT did, head, rev, public_key FROM repositories ORDER BY did")
              exported (tx #(repo/export-car % did))]
          (control/enqueue! fixture/*ds* settings did (:operation_cid (control-test/stored did)))
          (signing/enqueue! fixture/*ds* settings (:did bob) (signing/public-key (:public_key (first (provision/rows "SELECT public_key FROM repositories WHERE did = ?" (:did bob))))))
          (reset! mode :ignore)
          (is (= "RegistrationPending" (control-test/error #(accounts/create! fixture/*ds* settings (assoc (provision/signup) "handle" "waiting.example.com" "email" "waiting@example.com")))))
          (reset! mode :accept)
          (reserved/reserve! fixture/*ds* settings {})
          (register! old)
          (let [saved (secrets) events (provision/scalar "SELECT count(*) AS n FROM repo_events")
                result (rewrap old new)]
            (is (= "completed" (:state result)))
            (is (= 4 (get-in result [:counts :repositories.signing_key])))
            (is (= 3 (get-in result [:counts :plc_identities.rotation_key])))
            (is (= 1 (get-in result [:counts :handle_updates.next_rotation_key])))
            (is (= 1 (get-in result [:counts :handle_updates.next_signing_key])))
            (is (= 1 (get-in result [:counts :account_totp.sealed_secret])))
            (is (= 1 (get-in result [:counts :app_passwords])))
            (is (= 1 (get-in result [:counts :reserved_signing_keys])))
            (is (= 0 (provision/scalar "SELECT count(*) AS n FROM reserved_signing_keys")))
            (doseq [{:keys [table column purpose]} keys/columns
                    [prior after] (map vector (get saved [table column]) (get (secrets) [table column]))]
              (is (not= (:sealed prior) (:sealed after)))
              (is (= (vec (crypto/unseal old (purpose (:did prior)) (byte-array (:sealed prior))))
                     (vec (crypto/unseal new (purpose (:did after)) (byte-array (:sealed after))))))
              (is (thrown? Exception (crypto/unseal old (purpose (:did after)) (byte-array (:sealed after))))))
            (is (= events (provision/scalar "SELECT count(*) AS n FROM repo_events")))
            (is (= (mapv #(update % :public_key vec) before)
                   (mapv #(update % :public_key vec) (provision/rows "SELECT did, head, rev, public_key FROM repositories WHERE head IS NOT NULL ORDER BY did"))))
            (is (= (vec exported) (vec (tx #(repo/export-car % did)))))
            (is (= 0 (provision/scalar "SELECT count(*) AS n FROM sessions")))
            (is (= 0 (provision/scalar "SELECT count(*) AS n FROM refresh_tokens")))
            (is (= 0 (provision/scalar "SELECT count(*) AS n FROM app_passwords")))
            (is (= "MasterKeyMismatch" (error #(register! old))))
            (is (= (keys/fingerprint new) (:fingerprint (register! new))))
            (is (= result (rewrap old new)))
            (is (= "InvalidToken" (control-test/error #(tx (fn [conn] (auth/authenticate! conn updated {:headers {"authorization" (str "Bearer " (:accessJwt primary))}}))))))
            (is (nil? (tx #(passwords/find-password % updated did (:password app-password)))))
            (with-redefs [factors/now (constantly (+ (factors/now) 30))]
              (is (= {:valid? true} (tx #(factors/verify! % updated did (totp/code secret (quot (factors/now) 30)))))))
            (is (= :updated (handles/process-one! fixture/*ds* updated did)))
            (is (= :updated (handles/process-one! fixture/*ds* updated (:did bob))))
            (tx #(db/execute! % "UPDATE plc_identities SET available_at = now() WHERE status = 'pending'"))
            (is (= :ready (accounts/provision-one! fixture/*ds* updated nil)))
            (let [server (http/start! updated (app/handler updated fixture/*ds*))]
              (try
                (with-open [http-client (HttpClient/newHttpClient)]
                  (let [login (api/xrpc http-client (:port server) "POST" "com.atproto.server.createSession"
                                        {"identifier" "web.example.com" "password" "signup-password"} nil)
                        token (get-in login [:body "accessJwt"])
                        write (api/xrpc http-client (:port server) "POST" "com.atproto.repo.createRecord"
                                        {"repo" (:did web) "collection" "com.example.note" "record" {"$type" "com.example.note"}} token)
                        car (api/xrpc http-client (:port server) "GET" (str "com.atproto.sync.getRepo?did=" (:did web)) nil nil)
                        public (:public_key (first (provision/rows "SELECT public_key FROM repositories WHERE did = ?" (:did web))))]
                    (is (= 200 (:status login) (:status write) (:status car)))
                    (is (= 1 (count (:records (repository/verify-car (:raw car) (:did web) {:algorithm "ES256" :public public})))))))
                (finally ((:stop! server)))))))))))

(deftest corrupt-material-or-late-failure-rolls-back-every-secret-and-credential
  (let [settings (api/settings) old (:master-key settings) new (crypto/random-bytes 32)
        account (accounts/create! fixture/*ds* settings (provision/signup)) did (:did account)]
    (tx #(factors/begin! % settings did))
    (register! old)
    (let [initial (secrets) execute! db/execute!]
      (with-redefs [db/execute! (fn [conn sql & args]
                                (when (.startsWith ^String sql "INSERT INTO master_key_rotations") (throw (ex-info "Simulated receipt failure" {})))
                                (apply execute! conn sql args))]
        (is (thrown? Exception (rewrap old new))))
      (is (= initial (secrets)))
      (is (= 1 (provision/scalar "SELECT count(*) AS n FROM sessions")))
      (is (= 0 (:generation (keys/status! fixture/*ds*))))
      (tx #(db/execute! % "UPDATE account_totp SET sealed_secret = ? WHERE did = ?" (byte-array [1 2 3]) did))
      (let [corrupt (secrets)]
        (is (= "InvalidSealedMaterial" (error #(rewrap old new))))
        (is (= corrupt (secrets)))
        (is (= 1 (provision/scalar "SELECT count(*) AS n FROM sessions")))
        (is (= 0 (provision/scalar "SELECT count(*) AS n FROM master_key_rotations")))))))

(deftest leases-reject-online-rewrap-and-wrong-startup-keys
  (let [settings (api/settings) old (:master-key settings) new (crypto/random-bytes 32)]
    (accounts/create! fixture/*ds* settings (provision/signup))
    (is (= "InvalidSealedMaterial" (error #(register! new))))
    (is (= {:state "unregistered"} (keys/status! fixture/*ds*)))
    (with-open [a (keys/open-lease! fixture/*ds* old) b (keys/open-lease! fixture/*ds* old)]
      (is (true? (keys/live? a)))
      (is (true? (keys/live? b)))
      (is (= "PdsRunning" (error #(rewrap old new))))
      (is (= "MasterKeyMismatch" (error #(register! new))))
      (.close a)
      (is (false? (keys/live? a)))
      (is (true? (keys/live? b)))
      (is (= "PdsRunning" (error #(rewrap old new)))))
    (is (= "completed" (:state (rewrap old new))))
    (is (= "MasterKeyMismatch" (error #(register! old))))
    (is (= 1 (:generation (register! new))))))

(deftest retries-return-receipts-and-retired-keys-cannot-be-reused
  (let [old (crypto/random-bytes 32) next (crypto/random-bytes 32) newest (crypto/random-bytes 32)
        result (rewrap old next)]
    (is (= result (rewrap old next)))
    (is (= "MasterKeyMismatch" (error #(rewrap old newest))))
    (is (= "InvalidKey" (error #(rewrap next next))))
    (is (= "MasterKeyMismatch" (error #(keys/rewrap! fixture/*ds* next newest (keys/fingerprint old)))))
    (is (= 2 (:generation (rewrap next newest))))
    (is (= result (rewrap old next)))
    (is (= "RetiredKey" (error #(rewrap newest old))))
    (is (= "RetiredKey" (error #(rewrap newest next))))
    (is (= (keys/fingerprint newest) (:fingerprint (keys/status! fixture/*ds*))))))

(deftest shipped-cli-registers-rewraps-and-reports-only-public-state
  (let [old (crypto/random-bytes 32) new (crypto/random-bytes 32)
        settings (merge (api/settings) (auth/settings {"PDS_MASTER_KEY" (crypto/b64 old)}))
        environment {"PDS_MASTER_KEY" (crypto/b64 old) "PDS_NEW_MASTER_KEY" (crypto/b64 new)}]
    (accounts/create! fixture/*ds* settings (provision/signup))
    (is (= "unregistered" (get-in (child ["status"] {}) [:result :state])))
    (is (= 0 (:exit (child ["register"] environment))))
    (let [result (child ["rewrap" (keys/fingerprint old)] environment)]
      (is (= 0 (:exit result)))
      (is (= "completed" (get-in result [:result :state])))
      (is (not (.contains (pr-str result) (crypto/b64 old))))
      (is (not (.contains (pr-str result) (crypto/b64 new)))))
    (is (= (keys/fingerprint new) (get-in (child ["status"] {}) [:result :fingerprint])))
    (is (= "MasterKeyMismatch" (get-in (cli/run! ["register"] (merge (env) environment)) [:result :error])))))

(deftest maintenance-excludes-new-servers-and-concurrent-rewraps
  (let [settings (api/settings) old (:master-key settings) new (crypto/random-bytes 32)
        _ (accounts/create! fixture/*ds* settings (provision/signup))
        _ (register! old) entered (promise) release (promise) execute! db/execute!]
    (with-redefs [db/execute! (fn [conn sql & args]
                              (let [result (apply execute! conn sql args)]
                                (when (= sql "UPDATE repositories SET signing_key = ? WHERE did = ?")
                                  (deliver entered true)
                                  (when-not (= true (deref release 10000 :timeout)) (throw (ex-info "Test timed out" {}))))
                                result))]
      (let [maintenance (future (rewrap old new))]
        (try
          (is (= true (deref entered 10000 :timeout)))
          (is (= "MasterKeyBusy" (error #(register! old))))
          (is (= "MasterKeyBusy" (error #(register! new))))
          (is (= "PdsRunning" (error #(rewrap old new))))
          (is (= (keys/fingerprint old) (:fingerprint (keys/status! fixture/*ds*))))
          (deliver release true)
          (is (= "completed" (:state (deref maintenance 10000 :timeout))))
          (finally (deliver release true) (deref maintenance 10000 nil)))))))

(deftest oauth-access-and-refresh-survive-master-key-rewrapping
  (let [environment (oauth/env) issued (oauth/issue environment (oauth/approved environment))
        old (get-in environment [:settings :master-key]) new (crypto/random-bytes 32)
        updated (update environment :settings merge (auth/settings {"PDS_MASTER_KEY" (crypto/b64 new)}))
        grant (oauth/access issued)]
    (is (= "completed" (:state (rewrap old new))))
    (is (= grant (oauth/access issued)))
    (let [handler (app/handler (resource/settings updated) fixture/*ds*)
          challenge (resource/call handler (resource/request updated issued :get resource/session-path))
          response (resource/call handler (resource/request updated issued :get resource/session-path
                                                            {"nonce" (get-in challenge [:headers "DPoP-Nonce"])}))]
      (is (= 401 (:status challenge)))
      (is (= "use_dpop_nonce" (get-in challenge [:json "error"])))
      (is (= 200 (:status response)))
      (is (= (:did grant) (get-in response [:json "did"]))))
    (is (= "use_dpop_nonce" (proof/error #(oauth/issue updated (oauth/refresh-params issued)))))
    (let [refreshed (tokens/issue! fixture/*ds* (:resolver updated) (:settings updated)
                                    (merge (oauth/credentials updated) (oauth/refresh-params issued))
                                    (proof/sign (:key updated) (assoc (proof/claims) "nonce" (dpop/nonce (:settings updated)))))]
      (is (= (:did grant) (:did (oauth/access refreshed))))
      (is (= (:session-id grant) (:session-id (oauth/access refreshed)))))))

(deftest rewrapping-traverses-more-than-one-batch
  (let [old (crypto/random-bytes 32) new (crypto/random-bytes 32) key (crypto/keypair)]
    (tx (fn [conn]
          (doseq [i (range 103)]
            (let [handle (str "batch" i ".example.com") did (str "did:web:" handle)]
              (db/execute! conn "INSERT INTO accounts(did, handle, email, password_hash) VALUES (?, ?, ?, 'unused')" did handle (str "user@" handle))
              (db/execute! conn "INSERT INTO repositories(did, signing_key, public_key) VALUES (?, ?, ?)"
                           did (crypto/seal old did (:private key)) (:public key))))))
    (let [result (rewrap old new)]
      (is (= 103 (get-in result [:counts :repositories.signing_key])))
      (doseq [{:keys [did signing_key]} (provision/rows "SELECT did, signing_key FROM repositories")]
        (is (= (vec (:private key)) (vec (crypto/unseal new did signing_key))))))))
