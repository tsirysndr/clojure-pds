(ns pds.security.browser
  "Short-lived owner sessions for browser settings. Session -> account is the
  lock order. Account epoch changes revoke other sessions without locking them."
  (:require [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.oauth.sessions :as oauth-sessions]
            [pds.protocol.codec :as codec]
            [pds.security.factors :as factors]
            [pds.security.passkeys :as passkeys])
  (:import [java.security MessageDigest]
           [java.time Instant]
           [java.util Locale]))

(def lifetime 300)
(defn now [] (.getEpochSecond (Instant/now)))
(defn- time-now [] (Instant/ofEpochSecond (now)))
(defn- invalid! [] (errors/raise! 401 "BrowserSessionRequired" "Sign in again to continue"))
(defn- token? [s] (and (string? s) (boolean (re-matches #"[A-Za-z0-9_-]{43}" s))))
(defn- csrf [row token]
  (crypto/b64 (crypto/hmac (codec/utf8 token) (codec/utf8 (str "pds/security/csrf/v1/" (:csrf_nonce row))))))
(defn- load! [conn token]
  (when-not (token? token) (invalid!))
  (let [row (first (db/query conn "SELECT * FROM browser_sessions WHERE token_hash = ? FOR UPDATE" (crypto/digest-token token)))]
    (when-not (and row (.isAfter (.toInstant ^java.sql.Timestamp (:expires_at row)) (time-now))) (invalid!))
    (when-let [did (:did row)]
      (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" did))]
        (when-not (and (= "active" (:status account)) (= (:account_epoch row) (:oauth_epoch account))) (invalid!))))
    row))
(defn- check-csrf! [row token supplied]
  (when-not (and (token? supplied) (MessageDigest/isEqual (codec/utf8 (csrf row token)) (codec/utf8 supplied)))
    (errors/raise! 403 "InvalidCsrf" "Reload this page and try again")))
(defn- factor-type [conn account]
  (cond (factors/enabled? conn (:did account)) "totp" (:email_auth_factor account) "email"))
(defn- view [conn row token]
  (let [account (when (:did row) (first (db/query conn "SELECT * FROM accounts WHERE did = ?" (:did row))))
        stage (cond (:authenticated_at row) "authenticated" (:did row) "factor" :else "login")]
    (cond-> {:stage stage :csrf (csrf row token)}
      account (assoc :handle (:handle account) :factor (factor-type conn account))
      (= stage "authenticated") (assoc :passkeys (passkeys/list-credentials conn (:did row))
                                       :oauth-sessions (oauth-sessions/list! conn (:did row) nil)))))
(defn- output [conn token & [result]]
  {:token token :view (view conn (load! conn token) token) :result result})
(defn open! [ds token]
  (db/transact! ds
    (fn [conn]
      (let [existing (when (token? token)
                       (try (load! conn token)
                            (catch clojure.lang.ExceptionInfo e (when-not (= "BrowserSessionRequired" (:error (ex-data e))) (throw e)))))]
        (if existing (output conn token)
          (let [token (crypto/token) time (time-now)]
            (db/execute! conn "DELETE FROM browser_sessions WHERE token_hash IN
                               (SELECT token_hash FROM browser_sessions WHERE expires_at <= ? ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)" time)
            (db/execute! conn "INSERT INTO browser_sessions(token_hash, csrf_nonce, created_at, expires_at) VALUES (?, ?, ?, ?)"
                         (crypto/digest-token token) (crypto/token) time (.plusSeconds time lifetime))
            (output conn token)))))))
(defn- rotate! [conn row account method authenticated? & [preserve-lifetime?]]
  (let [token (crypto/token) time (time-now)]
    (db/execute! conn "DELETE FROM browser_sessions WHERE token_hash = ?" (:token_hash row))
    (db/execute! conn "INSERT INTO browser_sessions(token_hash, csrf_nonce, did, account_epoch, auth_method, authenticated_at, created_at, expires_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                 (crypto/digest-token token) (crypto/token) (:did account) (:oauth_epoch account) method
                 (when authenticated? (if preserve-lifetime? (.toInstant ^java.sql.Timestamp (:authenticated_at row)) time))
                 time (if preserve-lifetime? (.toInstant ^java.sql.Timestamp (:expires_at row)) (.plusSeconds time lifetime)))
    token))
(defn- identifier-account! [conn identifier]
  (when-not (and (string? identifier) (<= 1 (count identifier) 2048))
    (errors/raise! 401 "AuthenticationRequired" "Invalid identifier or password"))
  (let [identifier (.toLowerCase ^String identifier Locale/ROOT)]
    (first (db/query conn "SELECT * FROM accounts WHERE did = ? OR handle = ? OR email = ? FOR UPDATE" identifier identifier identifier))))
(defn- primary! [conn settings row account method]
  (let [factor (factor-type conn account)]
    (when (= "email" factor) (accounts/require-email! settings) (accounts/issue-email! conn account "sign-in"))
    (output conn (rotate! conn row account method (nil? factor)))))
(defn- login-stage! [row] (when (:did row) (errors/raise! 400 "AlreadySignedIn" "Sign out before choosing another account")))
(defn- account [conn row] (first (db/query conn "SELECT * FROM accounts WHERE did = ?" (:did row))))

(defn action! [ds settings token csrf-token action body]
  (db/transact! ds
    (fn [conn]
      (let [row (load! conn token) _ (check-csrf! row token csrf-token)]
        (case action
          "logout" (do (db/execute! conn "DELETE FROM browser_sessions WHERE token_hash = ?" (:token_hash row)) {:logout true})
          "login/password"
          (do (login-stage! row)
              (let [password (get body "password") account (identifier-account! conn (get body "identifier"))
                    matches? (and (string? password) (<= 1 (count password) 1024)
                                  (crypto/password-matches? password (or (:password_hash account) @accounts/dummy-password)))]
                (when-not (and matches? (= "active" (:status account)))
                  (errors/raise! 401 "AuthenticationRequired" "Invalid identifier or password"))
                (primary! conn settings row account "password")))
          "login/passkey/begin"
          (do (login-stage! row)
              (let [account (identifier-account! conn (get body "identifier"))]
                (when-not (= "active" (:status account)) (errors/raise! 401 "AuthenticationRequired" "Account is unavailable"))
                (let [result (passkeys/begin-authentication! conn settings (:did account) token)]
                  (db/execute! conn "UPDATE browser_sessions SET passkey_request = ? WHERE token_hash = ?" (:id result) (:token_hash row))
                  (output conn token result))))
          "login/passkey/finish"
          (do (login-stage! row)
              (when-not (= (:passkey_request row) (get body "id")) (errors/invalid! "Passkey request does not match this session"))
              (let [proof (passkeys/finish-authentication! conn settings (get body "id") token (get body "response"))]
                (if (:error proof) (output conn token proof)
                  (primary! conn settings row (first (db/query conn "SELECT * FROM accounts WHERE did = ?" (:did proof))) "passkey"))))
          "login/factor"
          (do (when-not (and (:did row) (nil? (:authenticated_at row))) (invalid!))
              (let [account (account conn row)
                    result (case (factor-type conn account)
                             "totp" (factors/verify! conn settings (:did row) (get body "code"))
                             "email" (do (accounts/consume-token! conn "sign-in" (get body "code") (:did row) (:email account)) {:valid? true})
                             (invalid!))]
                (if (:error result) (output conn token result)
                  (output conn (rotate! conn row account (:auth_method row) true)))))
          ;; Every management operation requires a completed owner login within
          ;; the five-minute absolute session lifetime, including all factors.
          (do
            (when-not (:authenticated_at row) (invalid!))
            (let [did (:did row)
                  result (case action
                           "oauth/list" {:oauth-sessions (oauth-sessions/list! conn did (get body "cursor"))}
                           "oauth/revoke" (oauth-sessions/revoke-owner! conn did (get body "id"))
                           "totp/begin" (factors/begin! conn settings did)
                           "totp/confirm" (factors/confirm! conn settings did (get body "code"))
                           "totp/disable" (factors/disable! conn settings did (get body "code"))
                           "passkeys/begin" (let [result (passkeys/begin-registration! conn settings did token (get body "name"))]
                                              (db/execute! conn "UPDATE browser_sessions SET passkey_request = ? WHERE token_hash = ?" (:id result) (:token_hash row)) result)
                           "passkeys/finish" (do (when-not (= (:passkey_request row) (get body "id")) (errors/invalid! "Passkey request does not match this session"))
                                                 (passkeys/finish-registration! conn settings (get body "id") token (get body "response")))
                           "passkeys/remove" (passkeys/remove! conn did (get body "id"))
                           "email/disable" (do (db/execute! conn "UPDATE accounts SET email_auth_factor = false WHERE did = ?" did)
                                               (db/execute! conn "DELETE FROM account_tokens WHERE did = ? AND purpose = 'sign-in'" did)
                                               (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ?" did)
                                               (db/execute! conn "DELETE FROM app_passwords WHERE did = ?" did)
                                               {:disabled true})
                           (errors/raise! 404 "NotFound" "Unknown account action"))
                  current (account conn row)]
              ;; Only this authenticated transaction may carry its owner session
              ;; forward across the security mutation it just performed.
              (if (not= (:oauth_epoch current) (:account_epoch row))
                (output conn (rotate! conn row current (:auth_method row) true true) result)
                (output conn token result)))))))))

(defn register!
  "Same-origin adapter must validate Origin before calling. Validate the anonymous
  browser and CSRF before signup; do not hold session/account locks across PLC I/O.
  Then recheck the browser and new primary credentials, including any new factor."
  [ds settings token csrf-token body]
  (db/transact! ds
    (fn [conn]
      (let [row (load! conn token)]
        (check-csrf! row token csrf-token)
        (login-stage! row))))
  (let [created (accounts/register! ds settings body)]
    (action! ds settings token csrf-token "login/password"
             {"identifier" (:did created) "password" (get body "password")})))

(defn owner!
  "Validate a recent, fully authenticated owner and its CSRF in an existing
  transaction. Used to bind browser authentication to explicit OAuth consent."
  [conn token csrf-token]
  (when (.getAutoCommit ^java.sql.Connection conn) (throw (ex-info "Browser owner requires a transaction" {})))
  (let [row (load! conn token)]
    (check-csrf! row token csrf-token)
    (when-not (:authenticated_at row) (invalid!))
    {:did (:did row) :account-epoch (:account_epoch row) :authenticated-at (:authenticated_at row)}))
