(ns pds.plc-provision-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.invites :as invites]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as directory-test]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))
(use-fixtures :each fixture/isolated-database)

(defn settings [client origin]
  (assoc (api/settings) :did-method :plc :http-client client :plc-url origin))
(defn signup [] {"handle" "alice.example.com" "email" "alice@example.com" "password" "signup-password"})
(defn rows [sql & params] (with-open [conn (db/connection fixture/*ds*)] (apply db/query conn sql params)))
(defn scalar [sql] (-> (rows sql) first :n))
(defn due! []
  (db/transact! fixture/*ds* #(db/execute! % "UPDATE plc_identities SET available_at = now(), lease_until = CASE WHEN lease_until IS NULL THEN NULL ELSE now() - interval '1 second' END")))

(deftest portable-signup-verifies-directory-before-publishing
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (assoc (settings client origin) :invite-required true)
            server (http/start! settings (app/handler settings fixture/*ds*))
            recovery (plc/did-key (crypto/keypair "ES256K"))
            invite (db/transact! fixture/*ds* #(invites/create! % "admin" 1))]
        (try
          (with-open [http (HttpClient/newHttpClient)]
            (let [created (api/xrpc http (:port server) "POST" "com.atproto.server.createAccount"
                                   (assoc (signup) "inviteCode" invite "recoveryKey" recovery) nil)
                  did (get-in created [:body "did"]) token (get-in created [:body "accessJwt"])
                  stored (first (rows "SELECT * FROM plc_identities WHERE did = ?" did))
                  op (codec/decode (:operation stored))
                  repo (first (rows "SELECT * FROM repositories WHERE did = ?" did))
                  signed (codec/decode (:content (first (rows "SELECT content FROM repo_blocks WHERE cid = ?" (:head repo)))))]
              (is (= 200 (:status created)))
              (is (re-matches #"did:plc:[a-z2-7]{24}" did))
              (is (= did (plc/genesis-did op)))
              (is (= "ready" (:status stored)))
              (is (= recovery (first (get op "rotationKeys"))))
              (is (= 2 (count (get op "rotationKeys"))))
              (is (= (plc/did-key {:algorithm "ES256" :public (:public_key repo)}) (get-in op ["verificationMethods" "atproto"])))
              (is (not= (get-in op ["verificationMethods" "atproto"]) (second (get op "rotationKeys"))))
              (is (crypto/verify "ES256" (:public_key repo) (codec/encode (dissoc signed "sig")) (get signed "sig")))
              (is (= did (get signed "did")))
              (is (= 1 (scalar "SELECT count(*) AS n FROM invite_uses")))
              (is (= 1 (scalar "SELECT count(*) AS n FROM email_outbox")))
              (is (= ["identity" "account" "commit"] (mapv :event_type (rows "SELECT event_type FROM repo_events ORDER BY seq"))))
              (is (= 1 (count (directory-test/posts calls))))
              (let [private (crypto/unseal (:master-key settings) (str did ":plc-rotation") (:rotation_key stored))
                    message (codec/utf8 "key continuity")]
                (is (= 32 (alength private)))
                (is (crypto/verify "ES256K" (:rotation_public stored) message (crypto/sign "ES256K" private message)))
                (is (thrown? Exception (crypto/unseal (:master-key settings) did (:rotation_key stored)))))
              (is (= did (get-in (api/xrpc http (:port server) "GET" "com.atproto.identity.resolveHandle?handle=alice.example.com" nil nil) [:body "did"])))
              (is (= (get-in created [:body "didDoc"])
                     (get-in (api/xrpc http (:port server) "GET" (str "com.atproto.identity.resolveDid?did=" did) nil nil) [:body "didDoc"])))
              (is (= 200 (:status (api/xrpc http (:port server) "GET" "com.atproto.server.getSession" nil token))))
              (is (= 400 (:status (api/xrpc http (:port server) "POST" "com.atproto.server.createAccount" (signup) nil)))
                  "Signup cannot bypass login policy after activation")
              (is (= 200 (:status (api/xrpc http (:port server) "POST" "com.atproto.server.requestAccountDelete" nil token))))
              (is (= 200 (:status (api/xrpc http (:port server) "POST" "com.atproto.server.deleteAccount"
                                           {"did" did "password" "signup-password" "token" (api/email-token "Confirm account deletion")} nil))))
              (is (= 0 (scalar "SELECT count(*) AS n FROM plc_identities")) "Account deletion removes the encrypted rotation key")
              (is (= 0 (scalar "SELECT count(*) AS n FROM repositories")))
              (is (= (get-in created [:body "didDoc"])
                     (get-in (api/xrpc http (:port server) "GET" (str "com.atproto.identity.resolveDid?did=" did) nil nil) [:body "didDoc"]))
                  "The portable DID still resolves from the directory after deleting local content")
              (is (= 1 (count (directory-test/posts calls))) "Account deletion does not publish a PLC tombstone")))
          (finally ((:stop! server))))))))

(deftest pending-signup-survives-worker-reconstruction-and-hides-unconfirmed-state
  (directory-test/with-directory
    (fn [{:keys [client origin mode calls]}]
      (let [settings (settings client origin) server (http/start! settings (app/handler settings fixture/*ds*))]
        (reset! mode :ignore)
        (try
          (with-open [http (HttpClient/newHttpClient)]
            (let [call #(api/xrpc http (:port server) %1 %2 %3 nil)
                  failed (call "POST" "com.atproto.server.createAccount" (signup))
                  account (first (rows "SELECT * FROM accounts")) did (:did account)]
              (is (= "RegistrationPending" (get-in failed [:body "error"])))
              (is (= "provisioning" (:status account)))
              (is (every? zero? [(scalar "SELECT count(*) AS n FROM sessions") (scalar "SELECT count(*) AS n FROM email_outbox")
                                (scalar "SELECT count(*) AS n FROM repo_events")]))
              (doseq [endpoint [(str "com.atproto.identity.resolveDid?did=" did) "com.atproto.identity.resolveHandle?handle=alice.example.com"
                                (str "com.atproto.sync.getRepoStatus?did=" did) (str "com.atproto.sync.getRepo?did=" did)]]
                (is (= 400 (:status (call "GET" endpoint nil)))))
              (is (= 401 (:status (call "POST" "com.atproto.server.createSession" {"identifier" did "password" "signup-password"}))))
              (is (= 400 (:status (call "POST" "com.atproto.server.createAccount" (assoc (signup) "password" "wrong-password")))))
              (is (= 1 (count (directory-test/posts calls))))
              (let [persisted (first (rows "SELECT operation, operation_cid FROM plc_identities"))
                    restarted (db/datasource fixture/*database*)]
                (due!)
                (reset! mode :accept)
                (is (= :ready (accounts/provision-one! restarted (assoc settings :plc-url "https://changed-directory.example.com") nil))
                    "A queued operation retains its original directory across configuration changes")
                (is (= (vec (:operation persisted)) (vec (:operation (first (rows "SELECT operation FROM plc_identities"))))))
                (is (= :ready (-> (rows "SELECT status FROM plc_identities") first :status keyword))))
              (is (= 200 (:status (call "POST" "com.atproto.server.createSession" {"identifier" did "password" "signup-password"}))))
              (is (= 3 (scalar "SELECT count(*) AS n FROM repo_events")))
              (is (= 1 (scalar "SELECT count(*) AS n FROM email_outbox")))
              (is (nil? (accounts/provision-one! fixture/*ds* settings did)))))
          (finally ((:stop! server))))))))

(deftest accepted-operation-with-local-rollback-is-reconciled-on-retry
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (settings client origin)]
        (with-redefs [repo/commit! (fn [& _] (throw (ex-info "Simulated local finalization failure" {})))]
          (is (thrown? Exception (accounts/create! fixture/*ds* settings (signup)))))
        (is (= "provisioning" (:status (first (rows "SELECT status FROM accounts")))))
        (is (= 0 (scalar "SELECT count(*) AS n FROM repo_events")))
        (is (= 1 (count (directory-test/posts calls))))
        (due!)
        (let [result (accounts/create! fixture/*ds* settings (signup))]
          (is (string? (:accessJwt result)))
          (is (= 1 (count (directory-test/posts calls))) "Directory acceptance is not repeated after local rollback")
          (is (= 3 (scalar "SELECT count(*) AS n FROM repo_events")))
          (is (= 1 (scalar "SELECT count(*) AS n FROM email_outbox"))))))))

(deftest concurrent-workers-cannot-publish-a-signup-twice
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (settings client origin) entered (promise) release (promise) attempts (atom 0)
            ensure! directory/ensure-operation!]
        (with-redefs [directory/ensure-operation! (fn [& args]
                                                   (when (= 1 (swap! attempts inc)) (deliver entered true) @release)
                                                   (apply ensure! args))]
          (let [creating (future (accounts/create! fixture/*ds* settings (signup)))]
            (try
              (is (= true (deref entered 10000 :timeout)))
              (is (nil? (accounts/provision-one! fixture/*ds* settings nil)))
              (is (= 0 (scalar "SELECT count(*) AS n FROM repo_events")))
              (due!)
              (is (= :ready (accounts/provision-one! fixture/*ds* settings nil)) "An expired lease can be recovered by another worker")
              (finally (deliver release true)))
            (is (map? (deref creating 15000 :timeout)))
            (is (= 1 (count (directory-test/posts calls))))
            (is (= 3 (scalar "SELECT count(*) AS n FROM repo_events")))))))))

(deftest invalid-invites-roll-back-before-directory-submission
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (assoc (settings client origin) :invite-required true)]
        (is (thrown? Exception (accounts/create! fixture/*ds* settings (assoc (signup) "inviteCode" "missing"))))
        (is (every? zero? [(scalar "SELECT count(*) AS n FROM accounts") (scalar "SELECT count(*) AS n FROM repositories")
                          (scalar "SELECT count(*) AS n FROM plc_identities")]))
        (is (empty? @calls))))))

(deftest signup-session-does-not-bypass-new-account-security-settings
  (directory-test/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (settings client origin) process! accounts/provision-one!]
        (with-redefs [accounts/provision-one!
                      (fn [& args]
                        (let [result (apply process! args)]
                          ;; Model another authenticated client changing the
                          ;; password immediately after activation, before the
                          ;; original createAccount request issues its session.
                          (db/transact! fixture/*ds* #(db/execute! % "UPDATE accounts SET password_hash = ?"
                                                       (crypto/password-hash "replacement-password")))
                          result))]
          (is (= "AuthenticationRequired"
                 (:error (try (accounts/create! fixture/*ds* settings (signup)) nil
                              (catch clojure.lang.ExceptionInfo e (ex-data e))))))
          (is (= 0 (scalar "SELECT count(*) AS n FROM sessions"))))
        (is (string? (:accessJwt (accounts/login! fixture/*ds* settings {"identifier" "alice.example.com" "password" "replacement-password"}))))))))
