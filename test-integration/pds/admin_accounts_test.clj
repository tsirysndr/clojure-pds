(ns pds.admin-accounts-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.admin-accounts :as admin]
            [pds.admin-test :as admin-test]
            [pds.app :as app]
            [pds.auth :as auth]
            [pds.blobs :as blobs]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.invites-test :as invites]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-tokens-test :as tokens]
            [pds.passkeys-test :as passkeys]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.security.factors :as factors]
            [pds.server-api-test :as api]
            [pds.totp-test :as totp])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)
(defn tx [f] (db/transact! fixture/*ds* f))
(defn rows [sql & args] (with-open [conn (db/connection fixture/*ds*)] (apply db/query conn sql args)))
(defn account [did] (first (rows "SELECT * FROM accounts WHERE did = ?" did)))
(defn settings [] (assoc (api/settings) :admin-password invites/admin-password :email-enabled false))
(defn request [token] {:headers {"authorization" (str "Bearer " token)}})
(defn endpoint [name] (str "com.atproto.admin." name))

(deftest administrative-password-recovery-and-authorization
  (let [settings (settings) alice (owner/account! settings)
        bob (accounts/create! fixture/*ds* settings (invites/signup "bob" nil))
        did (:did alice) access (:accessJwt alice)
        _ (tx #(accounts/issue-email! % (account did) "reset-password"))
        reset-token (api/email-token "Reset your PDS password")
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client (:port server) %1 %2 %3 %4)
              admin #(invites/admin-call client (:port server) (endpoint %1) %2)
              app-pass (get-in (call "POST" "com.atproto.server.createAppPassword" {"name" "phone"} access) [:body "password"])
              app-session (:body (call "POST" "com.atproto.server.createSession" {"identifier" did "password" app-pass} nil))
              body {"did" did "password" "replacement-password"}]
          (doseq [[name body] [["updateAccountPassword" body] ["updateAccountEmail" {"account" did "email" "new@example.com"}]
                               ["deleteAccount" {"did" did}]]]
            (doseq [token [nil access (get app-session "accessJwt")]]
              (is (= 401 (:status (call "POST" (endpoint name) body token)))))
            (let [disabled (app/handler (dissoc settings :admin-password) fixture/*ds*)]
              (is (= 403 (:status (disabled {:request-method :post :uri (str "/xrpc/" (endpoint name))
                                            :headers {"authorization" (admin-test/basic (str "admin:" invites/admin-password))}}))))))
          (doseq [bad [(dissoc body "did") (assoc body "did" "alice.example.com")
                       (assoc body "password" "short") (assoc body "password" (apply str (repeat 1025 "x")))]]
            (is (= 400 (:status (admin "updateAccountPassword" bad)))))
          (is (= "AccountNotFound" (get-in (admin "updateAccountPassword" (assoc body "did" "did:web:missing.example.com")) [:body "error"])))
          (is (= 200 (:status (call "GET" "com.atproto.server.getSession" nil access))))
          (is (= 200 (:status (admin "updateAccountPassword" body))))
          (doseq [token [access (get app-session "accessJwt")]]
            (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil token)))))
          (is (= 401 (:status (call "POST" "com.atproto.server.refreshSession" nil (:refreshJwt alice)))))
          (doseq [password ["correct-password" app-pass]]
            (is (= 401 (:status (call "POST" "com.atproto.server.createSession" {"identifier" did "password" password} nil)))))
          (is (= 200 (:status (call "POST" "com.atproto.server.createSession" {"identifier" did "password" "replacement-password"} nil))))
          (is (= 400 (:status (call "POST" "com.atproto.server.resetPassword" {"token" reset-token "password" "stolen-password"} nil))))
          (is (empty? (rows "SELECT * FROM account_tokens WHERE did = ?" did)))
          (is (empty? (rows "SELECT * FROM email_outbox")))
          (is (= "active" (:status (account did))))
          (is (= 200 (:status (call "GET" "com.atproto.server.getSession" nil (:accessJwt bob)))))))
      (finally ((:stop! server))))))

(deftest administrative-email-recovery-and-rollback
  (let [settings (settings) alice (owner/account! settings) did (:did alice)
        bob (accounts/create! fixture/*ds* settings (invites/signup "bob" nil))
        _ (tx #(do (db/execute! % "UPDATE accounts SET email_confirmed = true, email_auth_factor = true WHERE did = ?" did)
                   (accounts/issue-email! % (account did) "reset-password")))
        reset-token (api/email-token "Reset your PDS password") epoch (:oauth_epoch (account did))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [admin #(invites/admin-call client (:port server) (endpoint "updateAccountEmail") %)
              call #(api/xrpc client (:port server) %1 %2 %3 %4)]
          (doseq [body [{"account" did "email" "BOB@example.com"} {"account" did "email" "bad"}
                        {"account" "alice@example.com" "email" "new@example.com"} {"account" did}]]
            (is (= 400 (:status (admin body)))))
          (is (= epoch (:oauth_epoch (account did))))
          (is (= "alice@example.com" (:email (account did))))
          (is (true? (:email_auth_factor (account did))))
          (is (= 1 (count (rows "SELECT * FROM account_tokens WHERE did = ?" did))))
          (is (= 200 (:status (call "GET" "com.atproto.server.getSession" nil (:accessJwt alice)))))
          (is (= 200 (:status (admin {"account" "ALICE.example.com" "email" "NEW@example.com"}))))
          (let [row (account did)]
            (is (= "new@example.com" (:email row)))
            (is (false? (:email_confirmed row)))
            (is (false? (:email_auth_factor row)))
            (is (> (:oauth_epoch row) epoch)))
          (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil (:accessJwt alice)))))
          (is (= 400 (:status (call "POST" "com.atproto.server.resetPassword" {"token" reset-token "password" "stolen-password"} nil))))
          (is (= 401 (:status (call "POST" "com.atproto.server.createSession" {"identifier" "alice@example.com" "password" "correct-password"} nil))))
          (let [session (call "POST" "com.atproto.server.createSession" {"identifier" "new@example.com" "password" "correct-password"} nil)]
            (is (= 200 (:status session)))
            (is (= 200 (:status (admin {"account" did "email" "new@example.com"}))))
            (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil (get-in session [:body "accessJwt"]))))))
          (is (empty? (rows "SELECT * FROM email_outbox")) "Recovery does not require a working email transport")
          (is (= 200 (:status (call "GET" "com.atproto.server.getSession" nil (:accessJwt bob)))))))
      (finally ((:stop! server))))))

(defn oauth-recovery [method body]
  (let [env (tokens/env) settings (assoc (:settings env) :admin-password invites/admin-password)
        issued (tokens/issue env (tokens/approved env)) pending (tokens/approved env)
        state (owner/start) logged-in (owner/login settings state owner/credentials)
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (is (= owner/did (:did (tokens/access issued))))
        (is (= 200 (:status (invites/admin-call client (:port server) (endpoint method) body))))
        (is (= "invalid_grant" (owner/error #(tokens/access issued))))
        (is (= "invalid_grant" (owner/error #(tokens/issue env (tokens/refresh-params issued)))))
        (is (= "invalid_grant" (owner/error #(tokens/issue env pending))))
        (is (= "access_denied" (owner/error #(owner/decide settings (merge state logged-in) true)))))
      (finally ((:stop! server))))))

(deftest password-recovery-invalidates-oauth
  (oauth-recovery "updateAccountPassword" {"did" owner/did "password" "replacement-password"}))
(deftest email-recovery-invalidates-oauth
  (oauth-recovery "updateAccountEmail" {"account" owner/did "email" "recovered@example.com"}))
(deftest administrative-deletion-invalidates-oauth
  (oauth-recovery "deleteAccount" {"did" owner/did}))

(deftest recovery-retains-factors-and-deletion-removes-private-state
  (let [settings (settings) alice (owner/account! settings) did (:did alice)
        bob (accounts/create! fixture/*ds* settings (invites/signup "bob" nil))
        browser (crypto/token) credential (passkeys/register settings browser)
        recovery (:recovery-codes (totp/enroll settings))
        old-ceremony (passkeys/start-authentication settings browser)
        data (byte-array [1 2 3])
        store (reify blobs/ObjectStore
                (put-object! [_ _ _ _ _] {:object-key "alice/blob" :object-bucket "test-bucket"})
                (get-object! [_ _ _ _] data))
        stored (tx #(blobs/store! % {:blob-store store} did data "image/png"))
        _ (tx #(blobs/store! % {} (:did bob) data "image/png"))
        _ (tx #(repo/apply-writes! % settings (:did bob) [{:action :create :collection "com.example.file" :rkey "one"
                    :value {"$type" "com.example.file" "file" {"$type" "blob" "ref" {"$link" (:cid stored)} "mimeType" "image/png" "size" 3}}}] nil))
        _ (tx #(repo/apply-writes! % settings did [{:action :create :collection "com.example.file" :rkey "one"
                    :value {"$type" "com.example.file" "file" {"$type" "blob" "ref" {"$link" (:cid stored)} "mimeType" "image/png" "size" 3}}}] nil))
        _ (tx #(db/execute! % "INSERT INTO account_preferences(did, preferences) VALUES (?, '[]')" did))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [admin #(invites/admin-call client (:port server) (endpoint %1) %2)
              call #(api/xrpc client (:port server) %1 %2 %3 %4)]
          (is (= 200 (:status (admin "updateAccountPassword" {"did" did "password" "replacement-password"}))))
          (is (= 200 (:status (admin "updateAccountEmail" {"account" did "email" "recovered@example.com"}))))
          (is (true? (tx #(factors/enabled? % did))))
          (is (= 10 (count (rows "SELECT * FROM account_recovery_codes WHERE did = ?" did))))
          (is (= "AuthFactorTokenRequired" (get-in (call "POST" "com.atproto.server.createSession" {"identifier" did "password" "replacement-password"} nil) [:body "error"])))
          (is (= 200 (:status (call "POST" "com.atproto.server.createSession"
                                   {"identifier" did "password" "replacement-password" "authFactorToken" (first recovery)} nil))))
          (let [response (get (passkeys/authenticator (:options old-ceremony) :mode "authenticate" :credential credential) "response")]
            (is (= "InvalidPasskey" (owner/error #(passkeys/finish-authentication settings old-ceremony browser response)))))
          (let [state (passkeys/start-authentication settings browser)
                response (get (passkeys/authenticator (:options state) :mode "authenticate" :credential credential) "response")]
            (is (= did (:did (passkeys/finish-authentication settings state browser response)))))
          (is (= 200 (:status (admin "deleteAccount" {"did" did}))))
          (let [events (rows "SELECT * FROM repo_events WHERE did = ? ORDER BY seq" did)]
            (is (= "deleted" (get (codec/decode (:payload (last events))) "status")))
            (is (empty? (filter #(#{"commit" "sync"} (:event_type %)) events)))
            (is (= 200 (:status (admin "deleteAccount" {"did" did}))))
            (is (= (mapv :seq events) (mapv :seq (rows "SELECT * FROM repo_events WHERE did = ? ORDER BY seq" did)))))
          (doseq [table ["repositories" "records" "blobs" "sessions" "app_passwords" "account_tokens" "account_totp"
                        "account_recovery_codes" "account_passkeys" "account_webauthn_users" "webauthn_challenges" "account_preferences"]]
            (is (empty? (rows (str "SELECT * FROM " table " WHERE did = ?") did)) table))
          (is (= "deleted" (:status (account did))))
          (is (nil? (:password_hash (account did))))
          (is (nil? (:email (account did))))
          (is (every? :permanent (rows "SELECT permanent FROM handle_reservations WHERE did = ?" did)))
          (is (= [{:object_bucket "test-bucket" :object_key "alice/blob" :status "pending"}]
                 (rows "SELECT object_bucket, object_key, status FROM blob_delete_jobs WHERE did = ?" did)))
          (is (= "AccountNotFound" (get-in (admin "updateAccountPassword" {"did" did "password" "replacement-password"}) [:body "error"])))
          (is (= "AccountNotFound" (get-in (admin "updateAccountEmail" {"account" did "email" "new@example.com"}) [:body "error"])))
          (is (= "AccountNotFound" (get-in (admin "deleteAccount" {"did" "did:web:missing.example.com"}) [:body "error"])))
          (is (= 400 (:status (call "GET" (str "com.atproto.sync.getBlob?did=" did "&cid=" (:cid stored)) nil nil))))
          (is (= 200 (:status (call "GET" (str "com.atproto.sync.getBlob?did=" (:did bob) "&cid=" (:cid stored)) nil nil))))
          (is (= 200 (:status (call "GET" "com.atproto.server.getSession" nil (:accessJwt bob)))))))
      (finally ((:stop! server))))))

(deftest recovery-preserves-status-and-blocks-unfinished-identities
  (let [settings (settings) alice (owner/account! settings) did (:did alice)
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [admin #(invites/admin-call client (:port server) (endpoint %1) %2)]
          (doseq [status ["deactivated" "taken_down"]]
            (tx #(db/execute! % "UPDATE accounts SET status = ? WHERE did = ?" status did))
            (is (= 200 (:status (admin "updateAccountPassword" {"did" did "password" "replacement-password"}))))
            (is (= 200 (:status (admin "updateAccountEmail" {"account" did "email" "new@example.com"}))))
            (is (= status (:status (account did)))))
          (tx #(db/execute! % "INSERT INTO handle_updates(did, target_handle, external_handle, operation, operation_cid, directory_url)
                              VALUES (?, 'alice.example.com', false, ?, 'pending', 'https://plc.directory')" did (byte-array [1])))
          (is (= "IdentityUpdatePending" (get-in (admin "deleteAccount" {"did" did}) [:body "error"])))
          (is (some? (:password_hash (account did))))
          (tx #(do (db/execute! % "DELETE FROM handle_updates WHERE did = ?" did)
                   (db/execute! % "UPDATE accounts SET status = 'provisioning' WHERE did = ?" did)))
          (doseq [[method body] [["updateAccountPassword" {"did" did "password" "replacement-password"}]
                                ["updateAccountEmail" {"account" did "email" "new@example.com"}] ["deleteAccount" {"did" did}]]]
            (is (= "RegistrationPending" (get-in (admin method body) [:body "error"]))))
          (tx #(db/execute! % "UPDATE accounts SET status = 'taken_down' WHERE did = ?" did))
          (is (= 200 (:status (admin "deleteAccount" {"did" did}))))))
      (finally ((:stop! server))))))

(deftest recovery-serializes-with-authenticated-writes
  (let [settings (settings) alice (owner/account! settings) did (:did alice)
        locked (promise) release (promise)
        recovery (future (tx (fn [conn]
                              (admin/update-password! conn {"did" did "password" "replacement-password"})
                              (deliver locked true)
                              (when-not (= true (deref release 10000 :timeout)) (throw (ex-info "Test timed out" {}))))))]
    (try
      (is (= true (deref locked 10000 :timeout)))
      (let [started (promise)
            write (future (deliver started true)
                          (owner/error #(tx (fn [conn]
                                               (auth/authenticate! conn settings (request (:accessJwt alice)))
                                               (repo/apply-writes! conn settings did [{:action :create :collection "com.example.note" :rkey "late"
                                                                                      :value {"$type" "com.example.note"}}] nil)))))]
        (is (= true (deref started 10000 :timeout)))
        (is (= :blocked (deref write 100 :blocked)))
        (deliver release true)
        (deref recovery 10000 :timeout)
        (is (= "InvalidToken" (deref write 10000 :timeout)))
        (is (empty? (rows "SELECT * FROM records WHERE did = ?" did))))
      (finally (deliver release true) (deref recovery 10000 nil)))))

(deftest administrative-email-reaches-the-recipient-outbox
  (let [settings (assoc (settings) :email-enabled true)
        alice (accounts/create! fixture/*ds* settings (invites/signup "alice" nil))
        body {"recipientDid" (:did alice) "senderDid" (:service-did settings)
              "content" "Hello from your PDS operator"}
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [admin #(invites/admin-call client (:port server) (endpoint "sendEmail") %)]
          (is (= 401 (:status (api/xrpc client (:port server) "POST" "com.atproto.admin.sendEmail" body (:accessJwt alice)))))
          (is (= 400 (:status (admin (dissoc body "content")))))
          (is (= 400 (:status (admin (assoc body "content" "")))))
          (is (= 400 (:status (admin (assoc body "subject" "bad\r\nheader")))))
          (is (= 400 (:status (admin (assoc body "recipientDid" "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")))))
          (let [baseline (count (rows "SELECT 1 FROM email_outbox"))]
            (is (= {"sent" true} (:body (admin (assoc body "subject" "Operator notice")))))
            (is (= {"sent" true} (:body (admin body))))
            (let [payloads (filterv #(.contains ^String % "Hello from your PDS operator")
                                    (mapv :payload (rows "SELECT payload::text AS payload FROM email_outbox")))]
              (is (= (+ baseline 2) (count (rows "SELECT 1 FROM email_outbox"))))
              (is (= 2 (count payloads)))
              (is (every? #(.contains ^String % "alice@example.com") payloads))
              (is (some #(.contains ^String % "Operator notice") payloads))
              (is (some #(.contains ^String % "Message via your PDS") payloads))))
          (is (= "EmailUnavailable"
                 (try (tx #(admin/send-email! % (assoc settings :email-enabled false) body)) nil
                      (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))))
      (finally ((:stop! server))))))
