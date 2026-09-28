(ns pds.account-recovery-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.admin-accounts :as admin]
            [pds.admin-accounts-test :as admin-test]
            [pds.app :as app]
            [pds.app-passwords :as passwords]
            [pds.auth :as auth]
            [pds.browser-security-test :as browser-test]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.invites-test :as invites]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-tokens-test :as oauth]
            [pds.passkeys-test :as passkeys]
            [pds.plc-directory-test :as tls]
            [pds.plc-keys :as keys]
            [pds.plc-keys-test :as keys-test]
            [pds.plc-provision-test :as provision]
            [pds.repo :as repo]
            [pds.security.factors :as factors]
            [pds.server-api-test :as api]
            [pds.totp-test :as totp])
  (:import [java.net.http HttpClient]
           [java.util.concurrent TimeUnit]))
(use-fixtures :each fixture/isolated-database)
(def tx admin-test/tx)
(def rows admin-test/rows)
(def account admin-test/account)
(defn status [did] (tx #(admin/recovery-status! % did)))
(defn recover [did version reference password] (tx #(admin/recover-authenticators! % did version reference password)))
(defn child [args password]
  (let [builder (ProcessBuilder. ^java.util.List (into ["mise" "exec" "--" "clojure" "-M:account-admin"] args)) env (.environment builder)]
    (doseq [name ["PDS_MASTER_KEY" "PDS_ADMIN_PASSWORD" "PDS_RECOVERY_PASSWORD"]] (.remove env name))
    (.putAll env (fixture/database-env))
    (when password (.put env "PDS_RECOVERY_PASSWORD" password))
    (let [process (.start builder) output (future (slurp (.getInputStream process))) errors (future (slurp (.getErrorStream process)))]
      (try
        (is (.waitFor process 45 TimeUnit/SECONDS))
        (when-not (.isAlive process) {:exit (.exitValue process) :result (json/read-str (deref output 5000 "{}") :key-fn keyword)})
        (finally (when (.isAlive process) (.destroyForcibly process)) (deref output 5000 nil) (deref errors 5000 nil))))))

(deftest recovery-clears-lost-factors-revokes-access-and-preserves-account-data
  (let [settings (api/settings) alice (owner/account! settings) did (:did alice)
        bob (accounts/create! fixture/*ds* settings (invites/signup "bob" nil))
        browser (crypto/token) credential (passkeys/register settings browser)
        codes (:recovery-codes (totp/enroll settings))
        primary (accounts/login! fixture/*ds* settings (assoc owner/credentials "authFactorToken" (first codes)))
        pending (browser-test/login settings)
        owner-browser (browser-test/act settings pending "login/factor" {"code" (second codes)})
        app-password (tx #(passwords/create! % settings (assoc primary :access-scope "com.atproto.access") {"name" "lost-device"}))
        ceremony (passkeys/start-authentication settings browser)
        _ (tx #(accounts/issue-email! % (account did) "reset-password"))
        email-token (api/email-token "Reset your PDS password")
        before (status did) repo-before (first (rows "SELECT head, rev FROM repositories WHERE did = ?" did))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client (:port server) %1 %2 %3 %4)
              result (recover did (:securityVersion before) "support-42" "recovered-password")]
          (is (= "completed" (:state result)))
          (is (= (:role (first (rows "SELECT current_user AS role"))) (:databaseRole result)))
          (is (= (:factors before) (:removedFactors result)))
          (is (= (inc (:securityVersion before)) (:securityVersion result)))
          (is (= {:emailFactor false :totpEnrolled false :totpConfirmed false :recoveryCodes 0 :passkeys 0} (:factors (status did))))
          (doseq [table ["account_totp" "account_recovery_codes" "account_passkeys" "account_webauthn_users" "webauthn_challenges" "account_tokens" "app_passwords"]]
            (is (empty? (rows (str "SELECT * FROM " table " WHERE did = ?") did)) table))
          (is (= "active" (:status (account did))))
          (is (= "alice@example.com" (:email (account did))))
          (is (= repo-before (first (rows "SELECT head, rev FROM repositories WHERE did = ?" did))))
          (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil (:accessJwt primary)))))
          (is (= 401 (:status (call "POST" "com.atproto.server.refreshSession" nil (:refreshJwt primary)))))
          (doseq [password ["correct-password" (:password app-password)]]
            (is (= 401 (:status (call "POST" "com.atproto.server.createSession" {"identifier" did "password" password} nil)))))
          (is (= 400 (:status (call "POST" "com.atproto.server.resetPassword" {"token" email-token "password" "unwanted-password"} nil))))
          (is (= "BrowserSessionRequired" (owner/error #(browser-test/act settings owner-browser "totp/begin" {}))))
          (is (= "InvalidPasskey" (owner/error #(passkeys/finish-authentication settings ceremony browser
                               (get (passkeys/authenticator (:options ceremony) :mode "authenticate" :credential credential) "response")))))
          (is (= "InvalidToken" (:error (tx #(factors/verify! % settings did (nth codes 2))))))
          (is (= 200 (:status (call "GET" "com.atproto.server.getSession" nil (:accessJwt bob)))))
          (let [login (call "POST" "com.atproto.server.createSession" {"identifier" did "password" "recovered-password"} nil)]
            (is (= 200 (:status login)))
            (is (= 200 (:status (call "POST" "com.atproto.repo.createRecord"
                                     {"repo" did "collection" "com.example.note" "record" {"$type" "com.example.note"}}
                                     (get-in login [:body "accessJwt"]))))))
          ;; A completed operator command must never erase subsequent enrollment.
          (passkeys/register settings (crypto/token))
          (totp/enroll settings)
          (let [fresh (status did) hash (:password_hash (account did))]
            (is (= result (recover did (:securityVersion before) "support-42" "ignored-retry-password")))
            (is (= fresh (status did)))
            (is (= hash (:password_hash (account did))))
            (is (= "RecoveryAlreadyCompleted" (owner/error #(recover did (:securityVersion before) "different-case" "another-password"))))
            (is (= 1 (count (rows "SELECT * FROM authenticator_recoveries")))))))
      (finally ((:stop! server))))))

(deftest recovery-revokes-oauth-access-refresh-codes-and-consent
  (let [env (oauth/env) settings (:settings env) issued (oauth/issue env (oauth/approved env))
        code (oauth/approved env) state (owner/start) authenticated (owner/login settings state owner/credentials)
        version (:securityVersion (status owner/did))]
    (recover owner/did version "oauth-recovery" "replacement-password")
    (is (= "invalid_grant" (owner/error #(oauth/access issued))))
    (is (= "invalid_grant" (owner/error #(oauth/issue env (oauth/refresh-params issued)))))
    (is (= "invalid_grant" (owner/error #(oauth/issue env code))))
    (is (= "access_denied" (owner/error #(owner/decide settings (merge state authenticated) true))))
    (is (seq (rows "SELECT * FROM oauth_sessions")) "Replay evidence remains available")))

(deftest recovery-preserves-inactive-status-and-disables-email-factor-without-email-service
  (let [settings (assoc (api/settings) :email-enabled false) alice (owner/account! settings) did (:did alice)]
    (doseq [[index state] (map-indexed vector ["active" "deactivated" "taken_down"])]
      (tx #(db/execute! % "UPDATE accounts SET status = ?, email_confirmed = true, email_auth_factor = true WHERE did = ?" state did))
      (let [before (status did) result (recover did (:securityVersion before) (str "case-" index) (str "replacement-password-" index))]
        (is (true? (get-in result [:removedFactors :emailFactor])))
        (is (= state (:status (account did))))
        (is (true? (:email_confirmed (account did))))
        (is (false? (:email_auth_factor (account did))))))))

(deftest stale-versions-invalid-passwords-and-rollback-leave-credentials-intact
  (let [settings (api/settings) alice (owner/account! settings) did (:did alice)
        _ (totp/enroll settings) before (status did) hash (:password_hash (account did))]
    (doseq [[password error] [["short" "InvalidPassword"] ["correct-password" "InvalidRequest"]]]
      (is (= error (owner/error #(recover did (:securityVersion before) "case" password)))))
    (is (= "SecurityVersionMismatch" (owner/error #(recover did (dec (:securityVersion before)) "case" "replacement-password"))))
    (let [query db/query]
      (with-redefs [db/query (fn [conn sql & args]
                              (when (.startsWith ^String sql "INSERT INTO authenticator_recoveries") (throw (ex-info "Receipt storage failed" {})))
                              (apply query conn sql args))]
        (is (thrown? Exception (recover did (:securityVersion before) "case" "replacement-password")))))
    (is (= before (status did)))
    (is (= hash (:password_hash (account did))))
    (is (empty? (rows "SELECT * FROM authenticator_recoveries")))
    (is (= "AccountNotFound" (owner/error #(recover "did:web:missing.example.com" 0 "case" "replacement-password"))))
    (tx #(db/execute! % "UPDATE accounts SET status = 'provisioning' WHERE did = ?" did))
    (is (= "RegistrationPending" (owner/error #(recover did (:oauth_epoch (account did)) "case" "replacement-password"))))
    (tx #(db/execute! % "UPDATE accounts SET status = 'deleted' WHERE did = ?" did))
    (is (= "AccountNotFound" (owner/error #(status did))))))

(deftest concurrent-retries-perform-one-recovery
  (let [settings (api/settings) alice (owner/account! settings) did (:did alice)
        before (status did) start (promise)
        workers (mapv (fn [_] (future @start (recover did (:securityVersion before) "same-case" "replacement-password"))) (range 4))]
    (deliver start true)
    (let [results (mapv #(deref % 20000 :timeout) workers)]
      (is (every? map? results))
      (is (apply = results)))
    (is (= (inc (:securityVersion before)) (:securityVersion (status did))))
    (is (= 1 (count (rows "SELECT * FROM authenticator_recoveries"))))))

(deftest pending-identity-work-is-preserved
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)]
        (keys/enqueue! fixture/*ds* settings did (:operation_cid (keys-test/stored did)))
        (let [job (keys-test/job did) before (status did)]
          (recover did (:securityVersion before) "identity-recovery" "replacement-password")
          (is (= (:operation_cid job) (:operation_cid (keys-test/job did))))
          (is (= (vec (:next_rotation_key job)) (vec (:next_rotation_key (keys-test/job did)))))
          (is (= "completed" (:state (keys-test/rotate settings did (:operation_cid (keys-test/stored did)))))))))))

(deftest shipped-recovery-cli-needs-no-master-key-or-email-service
  (let [settings (api/settings) alice (owner/account! settings) did (:did alice)
        before (child ["status" did] nil)
        command ["recover-authenticators" did (str (get-in before [:result :securityVersion])) "support-123"]]
    (is (= 0 (:exit before)))
    (let [result (child command "replacement-password")]
      (is (= 0 (:exit result)))
      (is (= "completed" (get-in result [:result :state])))
      (is (not (.contains (pr-str result) "replacement-password")))
      (is (= result (child command "replacement-password"))))
    (is (= did (:did (accounts/login! fixture/*ds* settings {"identifier" did "password" "replacement-password"}))))))

(deftest recovery-does-not-deadlock-a-browser-already-waiting-for-the-account
  ;; Interleaves two writers around a `FOR UPDATE` account lock. SQLite
  ;; serializes writers for the whole transaction, so the interleaving under
  ;; test cannot be constructed there.
  (when (fixture/postgres?)
  (let [settings (api/settings) _ (owner/account! settings) did owner/did
        browser (browser-test/login settings) before (status did)
        entered (promise) release (promise) query db/query]
    (with-redefs [db/query (fn [conn sql & args]
                            (when (= sql "SELECT * FROM accounts WHERE did = ? FOR UPDATE")
                              (deliver entered true)
                              (when-not (= true (deref release 10000 :timeout)) (throw (ex-info "Test timed out" {}))))
                            (apply query conn sql args))]
      (let [request (future (owner/error #(browser-test/act settings browser "totp/begin" {})))]
        (try
          (is (= true (deref entered 10000 :timeout)))
          (let [recovery (future (recover did (:securityVersion before) "browser-race" "replacement-password"))]
            (try
              (is (= "completed" (:state (deref recovery 5000 :timeout))))
              (deliver release true)
              (is (= "BrowserSessionRequired" (deref request 10000 :timeout)))
              (is (empty? (rows "SELECT * FROM account_totp WHERE did = ?" did)))
              (finally (deliver release true) (deref recovery 10000 nil))))
          (finally (deliver release true) (deref request 10000 nil))))))))
