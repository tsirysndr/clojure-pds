(ns pds.migration-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.email :as email]
            [pds.http :as http]
            [pds.identity :as identity]
            [pds.invites :as invites]
            [pds.migration :as migration]
            [pds.moderation :as moderation]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as directory-test]
            [pds.plc-provision-test :as provision :refer [rows scalar]]
            [pds.plc-test :as plc-test]
            [pds.protocol.codec :as codec]
            [pds.server-api-test :as api]
            [pds.service-auth :as service-auth]
            [pds.service-auth-test :refer [document token-request error]])
  (:import [java.net.http HttpClient]))
(use-fixtures :each fixture/isolated-database)

(defn source! [client origin]
  (let [rotation (crypto/keypair "ES256K") key (crypto/keypair "ES256K")
        op (plc/sign-operation (assoc (plc-test/unsigned [rotation] key)
                                      "services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" "https://source.example.net"}}) rotation)
        did (plc/genesis-did op)]
    (directory/ensure-operation! client origin did op)
    {:did did :key key :rotation rotation :operation op}))
(defn authorization [settings source]
  (service-auth/sign (:key source) (:did source) (str (:service-did settings) "#atproto_pds")
                     "com.atproto.server.createAccount" (auth/now) (+ (auth/now) 60)))
(defn input [source] (assoc (provision/signup) "did" (:did source)))

(deftest destination-creation-prepares-inactive-plc-account-and-new-credentials
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (assoc (provision/settings client origin) :invite-required true)
            source (source! client origin) did (:did source)
            invitation (db/transact! fixture/*ds* #(invites/create! % "admin" 1))
            body (assoc (input source) "inviteCode" invitation)
            token (authorization settings source)
            server (http/start! settings (app/handler settings fixture/*ds*))]
        (try
          (with-open [http (HttpClient/newHttpClient)]
            (let [call #(api/xrpc http (:port server) %1 %2 %3 %4)
                  create #(call "POST" "com.atproto.server.createAccount" body %)
                  no-auth (create nil)]
              (is (= 401 (:status no-auth)))
              (is (= 0 (scalar "SELECT count(*) AS n FROM accounts")))
              (let [response (create token) result (:body response) access (get result "accessJwt")
                    account (first (rows "SELECT * FROM accounts WHERE did = ?" did))
                    repo (first (rows "SELECT * FROM repositories WHERE did = ?" did))
                    prepared (first (rows "SELECT * FROM plc_identities WHERE did = ?" did))
                    rec (:body (call "GET" "com.atproto.identity.getRecommendedDidCredentials" nil access))]
                (is (= 200 (:status response)))
                (is (= did (get result "did")))
                (is (= "deactivated" (:status account)))
                (is (false? (get result "active")))
                (is (= (plc/did-document (plc/operation-data did (:operation source))) (get result "didDoc")))
                (is (= "prepared" (:status prepared)))
                (is (= 0 (scalar "SELECT count(*) AS n FROM repo_events")))
                (is (= 1 (scalar "SELECT count(*) AS n FROM invite_uses")))
                (is (= 1 (scalar "SELECT count(*) AS n FROM service_token_uses")))
                (is (= 1 (scalar "SELECT count(*) AS n FROM email_outbox")))
                (is (= 1 (count (directory-test/posts calls))) "Destination creation does not publish a PLC operation")
                (is (= "JwtReplay" (get-in (create token) [:body "error"])))
                (is (= 200 (:status (call "POST" "com.atproto.server.createSession" {"identifier" did "password" "signup-password"} nil))))
                (is (= 200 (:status (call "POST" "com.atproto.server.refreshSession" nil (get result "refreshJwt")))))
                (is (= "MigrationIncomplete" (get-in (call "POST" "com.atproto.server.activateAccount" nil access) [:body "error"])))
                (is (= "MigrationIncomplete" (error #(db/transact! fixture/*ds*
                                                        (fn [conn] (moderation/update-status! conn {"subject" {"$type" "com.atproto.admin.defs#repoRef" "did" did}
                                                                                                   "deactivated" {"applied" false}}))))))
                (is (= 400 (:status (call "GET" (str "com.atproto.sync.getRepo?did=" did) nil nil))))
                (is (= 401 (:status (call "POST" "com.atproto.repo.applyWrites" {"repo" did "writes" []} access))))
                (is (= (plc/did-key {:algorithm "ES256" :public (:public_key repo)}) (get-in rec ["verificationMethods" "atproto"])))
                (is (not= (plc/did-key (:key source)) (get-in rec ["verificationMethods" "atproto"])))
                (is (= [(plc/did-key {:algorithm "ES256K" :public (:rotation_public prepared)})] (get rec "rotationKeys")))
                (is (false? (:repository_imported (first (rows "SELECT repository_imported FROM account_imports WHERE did = ?" did)))))
                (let [operation (plc-test/update-op (:operation source) (:rotation source) rec)]
                  (is (= 200 (:status (call "POST" "com.atproto.identity.submitPlcOperation" {"operation" operation} access))))
                  (is (= (plc/operation-cid operation) (:head (directory/audit! client origin did))))
                  (is (= "ready" (:status (first (rows "SELECT status FROM plc_identities WHERE did = ?" did)))))
                  (is (= "deactivated" (:status (first (rows "SELECT status FROM accounts WHERE did = ?" did)))))
                  (is (= "MigrationIncomplete" (get-in (call "POST" "com.atproto.server.activateAccount" nil access) [:body "error"])))
                  (is (= 0 (scalar "SELECT count(*) AS n FROM repo_events WHERE event_type IN ('commit', 'sync', 'account')")))))))
          (finally ((:stop! server))))))))

(deftest destination-creation-rolls-back-nonce-invite-account-and-outbox-together
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (assoc (provision/settings client origin) :invite-required true)
            resolver (identity/resolver settings) source (source! client origin)
            token (authorization settings source) request (token-request token)
            invitation (db/transact! fixture/*ds* #(invites/create! % "admin" 1))
            body (assoc (input source) "inviteCode" invitation)]
        (is (= "InvalidInviteCode" (error #(migration/create! fixture/*ds* settings resolver request (assoc body "inviteCode" "missing")))))
        (with-redefs [email/enqueue! (fn [& _] (throw (ex-info "Outbox failed" {})))]
          (is (thrown? Exception (migration/create! fixture/*ds* settings resolver request body))))
        (doseq [table ["accounts" "repositories" "account_imports" "plc_identities" "handle_reservations" "service_token_uses" "invite_uses" "email_outbox" "repo_events"]]
          (is (= 0 (scalar (str "SELECT count(*) AS n FROM " table)))))
        (is (= (:did source) (:did (migration/create! fixture/*ds* settings resolver request body))))
        (is (= "HandleNotAvailable" (error #(migration/create! fixture/*ds* settings resolver (token-request (authorization settings source)) body))))
        (is (= 1 (scalar "SELECT count(*) AS n FROM service_token_uses")))
        (is (= 1 (scalar "SELECT count(*) AS n FROM invite_uses")))
        (is (= 1 (count (directory-test/posts calls))))))))

(deftest web-import-requires-issuer-and-custom-handle-proof
  (let [source {:did "did:web:Owner.Example.NET" :key (crypto/keypair "ES256")}
        doc (document (:did source) (:key source)) binding (atom (:did source))
        settings (assoc (api/settings) :fetch (fn [_ _] {:status 200 :body (codec/utf8 (json/write-str doc))})
                        :txt-lookup (fn [_] [(str "did=" @binding)]))
        resolver (identity/resolver settings) request (token-request (authorization settings source))
        body (assoc (input source) "handle" "custom.example.net")]
    (is (= "AuthenticationRequired" (error #(migration/create! fixture/*ds* settings resolver request (assoc body "did" "did:web:other.example.net")))))
    (reset! binding "did:web:other.example.net")
    (is (= "InvalidHandle" (error #(migration/create! fixture/*ds* settings resolver request body))))
    (is (= 0 (scalar "SELECT count(*) AS n FROM service_token_uses")))
    (reset! binding (:did source))
    (let [result (migration/create! fixture/*ds* settings resolver request body)]
      (is (= doc (:didDoc result)))
      (is (= "deactivated" (:status result)))
      (is (= 0 (scalar "SELECT count(*) AS n FROM plc_identities")))
      (is (= [{:handle "custom.example.net" :permanent false} {:handle "owner.example.net" :permanent true}]
             (rows "SELECT handle, permanent FROM handle_reservations ORDER BY handle")))
      (let [handler (app/handler settings fixture/*ds*)
            resolve-doc #(json/read-str (:body (handler {:request-method :get :uri "/xrpc/com.atproto.identity.resolveDid"
                                                         :query-string (str "did=" (:did source))})))]
        (is (= doc (get (resolve-doc) "didDoc")))
        (db/transact! fixture/*ds* #(accounts/issue-email! % (first (db/query % "SELECT * FROM accounts WHERE did = ?" (:did source))) "delete-account"))
        (accounts/delete! fixture/*ds* {"did" (:did source) "password" "signup-password" "token" (api/email-token "Confirm account deletion")})
        (is (= 0 (scalar "SELECT count(*) AS n FROM account_imports")))
        (is (= doc (get (resolve-doc) "didDoc")) "Deleting the local account does not deactivate a foreign web DID")))))

(deftest concurrent-destination-creation-reserves-only-one-account-and-invite-use
  (directory-test/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (assoc (provision/settings client origin) :invite-required true)
            resolver (identity/resolver settings) source (source! client origin)
            invitation (db/transact! fixture/*ds* #(invites/create! % "admin" 2))
            body (assoc (input source) "inviteCode" invitation)
            requests (repeatedly 2 #(token-request (authorization settings source)))
            tasks (mapv (fn [request] (future (try (migration/create! fixture/*ds* settings resolver request body)
                                                  (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))) requests)
            results (mapv #(deref % 15000 :timeout) tasks)]
        (is (= 1 (count (filter map? results))))
        (is (= 1 (count (filter #{"HandleNotAvailable"} results))))
        (doseq [table ["accounts" "account_imports" "service_token_uses" "invite_uses" "email_outbox"]]
          (is (= 1 (scalar (str "SELECT count(*) AS n FROM " table)))))))))

(deftest source-key-rotation-during-destination-preparation-requires-fresh-proof
  (directory-test/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) resolver (identity/resolver settings)
            source (source! client origin) request (token-request (authorization settings source))
            rotated (plc-test/update-op (:operation source) (:rotation source)
                      {"verificationMethods" {"atproto" (plc/did-key (crypto/keypair "ES256"))}})
            verify! service-auth/verify!]
        (with-redefs [service-auth/verify! (fn [& args]
                                           (let [proof (apply verify! args)]
                                             (directory/ensure-operation! client origin (:did source) rotated)
                                             proof))]
          (is (= "IdentityMismatch" (error #(migration/create! fixture/*ds* settings resolver request (input source))))))
        (is (= 0 (scalar "SELECT count(*) AS n FROM accounts")))
        (is (= 0 (scalar "SELECT count(*) AS n FROM service_token_uses")))))))
