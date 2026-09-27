(ns pds.accounts
  (:require [clojure.string :as str]
            [pds.app-passwords :as app-passwords]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.email :as email]
            [pds.errors :as errors]
            [pds.events :as events]
            [pds.invites :as invites]
            [pds.oauth.permissions :as permissions]
            [pds.handle-registry :as handles]
            [pds.identity :as identity]
            [pds.plc :as plc]
            [pds.plc-provision :as provision]
            [pds.protocol.codec :as codec]
            [pds.protocol.formats :as formats]
            [pds.protocol.syntax :as syntax]
            [pds.repo :as repo]
            [pds.request :as request]
            [pds.security.factors :as factors])
  (:import [java.net URI]
           [java.time Instant]))

(defn settings [env]
  (let [domain (get env "PDS_USER_DOMAIN" (get env "PDS_HOSTNAME" "pds.localhost"))
        url (get env "PDS_PUBLIC_URL" "http://localhost:3000")
        uri (try (URI/create url) (catch Exception _ nil))
        signup (get env "PDS_ENABLE_SIGNUP" "false")
        method (get env "PDS_DID_METHOD" "web")]
    (when-not (#{"web" "plc"} method) (throw (ex-info "PDS_DID_METHOD must be web or plc" {})))
    (when-not (syntax/handle? domain) (throw (ex-info "PDS_USER_DOMAIN must be a DNS domain" {})))
    (when-not (#{"true" "false"} signup) (throw (ex-info "PDS_ENABLE_SIGNUP must be true or false" {})))
    (when-not (and uri (.getHost uri) (nil? (.getUserInfo uri)) (nil? (.getQuery uri)) (nil? (.getFragment uri))
                   (#{"" "/"} (.getPath uri))
                   (or (= "https" (.getScheme uri))
                       (and (= "http" (.getScheme uri)) (#{"localhost" "127.0.0.1"} (.getHost uri)))))
      (throw (ex-info "PDS_PUBLIC_URL must be an HTTPS origin (loopback HTTP allowed)" {})))
    (when (and (= "plc" method) (or (not= "https" (.getScheme uri)) (not (identity/resolvable-handle? domain))))
      (throw (ex-info "PLC signup requires an HTTPS public URL and a resolvable user domain" {})))
    {:user-domain (str/lower-case domain) :public-url (str/replace url #"/$" "")
     :signup-enabled (= "true" signup) :did-method (keyword method)}))

(defn public-account [account]
  (cond-> {:did (:did account) :handle (:handle account) :email (:email account)
           :emailConfirmed (:email_confirmed account) :emailAuthFactor (boolean (:email_auth_factor account))
           :active (= "active" (:status account))}
    (not (permissions/email? account)) (dissoc :email :emailConfirmed :emailAuthFactor)
    (not= "active" (:status account)) (assoc :status (if (= "taken_down" (:status account)) "takendown" (:status account)))))
(defn did-document
  ([settings account public-key]
  {:id (:did account) :alsoKnownAs [(str "at://" (:handle account))]
   :verificationMethod [{:id (str (:did account) "#atproto") :type "Multikey"
                         :controller (:did account) :publicKeyMultibase (crypto/multikey "ES256" public-key)}]
   :service [{:id (str (:did account) "#atproto_pds") :type "AtprotoPersonalDataServer"
              :serviceEndpoint (:public-url settings)}]})
  ([conn settings account public-key]
   (if (str/starts-with? (:did account) "did:plc:")
     (let [row (first (db/query conn "SELECT operation FROM plc_identities WHERE did = ? AND status = 'ready'" (:did account)))]
       (when-not row (errors/raise! 503 "DidUnavailable" "PLC identity is not confirmed"))
       (plc/did-document (plc/operation-data (:did account) (codec/decode (:operation row)))))
     (did-document settings account public-key))))
(defn resolve-account [conn identifier]
  (or (first (db/query conn "SELECT * FROM accounts WHERE (did = ? OR handle = ?) AND status = 'active'"
                      identifier (str/lower-case (request/string! identifier "repo"))))
      (errors/raise! 400 "RepoNotFound" "Account was not found")))
(defn resolve-identity [conn identifier]
  (or (first (db/query conn "SELECT * FROM accounts WHERE (did = ? OR handle = ?) AND status IN ('active', 'deactivated', 'taken_down')"
                      identifier (str/lower-case (request/string! identifier "identifier"))))
      (errors/raise! 400 "AccountNotFound" "Identity was not found")))
(defn password! [value]
  (when-not (and (string? value) (<= 8 (count value) 1024))
    (errors/raise! 400 "InvalidPassword" "Password must contain 8 to 1024 characters"))
  (crypto/password-hash value))

(defn issue-email! [conn account purpose]
  ;; Serialize callers on the account before issuing a token.
  (db/query conn "SELECT did FROM accounts WHERE did = ? FOR UPDATE" (:did account))
  (when (empty? (db/query conn "SELECT 1 FROM account_tokens WHERE did = ? AND purpose = ? AND created_at > now() - interval '60 seconds'"
                          (:did account) purpose))
    (let [token (crypto/token)
          subject (case purpose "confirm-email" "Confirm your PDS email" "reset-password" "Reset your PDS password"
                        "delete-account" "Confirm account deletion" "update-email" "Update your PDS email"
                        "sign-in" "Sign in to your PDS account" "plc-operation" "Authorize a PLC identity operation")
          minutes (if (= purpose "sign-in") 10 30)]
      (db/execute! conn "DELETE FROM account_tokens WHERE did = ? AND purpose = ?" (:did account) purpose)
      (db/execute! conn "INSERT INTO account_tokens(token_hash, did, purpose, email, expires_at) VALUES (?, ?, ?, ?, ?)"
                   (crypto/digest-token token) (:did account) purpose (:email account) (.plusSeconds (Instant/now) (* 60 minutes)))
      (email/enqueue! conn {:to (:email account) :subject subject
                           :text (str subject ".\n\n"
                                      (when (= purpose "plc-operation")
                                        "This code authorizes signing an identity change, including moving your account or replacing its control keys. Only enter it in a migration or identity-change flow you initiated. Do not share it.\n\n")
                                      "Your token is: " token "\n\nIt expires in " minutes " minutes. If you did not request this, ignore this email.")}))))

(defn provision-one! [ds settings did]
  (provision/process-one! ds settings did
    (fn [conn account]
      (db/execute! conn "UPDATE accounts SET status = 'active' WHERE did = ?" (:did account))
      (repo/commit! conn settings (assoc (repo/state conn (:did account)) :announce-handle (:handle account)))
      (when (:email-enabled settings) (issue-email! conn account "confirm-email")))))

(defn- create-plc! [ds settings body handle address hash issue-session?]
  (when-not (:http-client settings) (errors/raise! 503 "DirectoryUnavailable" "PLC directory client is unavailable"))
  (when (contains? body "recoveryKey")
    (try (plc/parse-key (get body "recoveryKey")) (catch Exception _ (errors/invalid! "Invalid PLC recovery key"))))
  (let [prepared (provision/prepare settings handle (get body "recoveryKey"))
        did (db/transact! ds
              (fn [conn]
                (if (pos? (db/execute! conn "INSERT INTO accounts(did, handle, email, password_hash, status)
                                             VALUES (?, ?, ?, ?, 'provisioning') ON CONFLICT DO NOTHING"
                                      (:did prepared) handle address hash))
                  (do (handles/reserve! conn (:did prepared) handle)
                      (invites/consume! conn settings (get body "inviteCode") (:did prepared))
                      (provision/reserve! conn prepared)
                      (:did prepared))
                  (let [account (first (db/query conn "SELECT * FROM accounts WHERE handle = ? FOR UPDATE" handle))
                        row (first (db/query conn "SELECT recovery_key FROM plc_identities WHERE did = ?" (:did account)))]
                    ;; Only the original account owner can retry a reservation.
                    ;; Never treat repeated signup as login after activation.
                    (when-not (and (= "provisioning" (:status account)) (= address (:email account))
                                   (crypto/password-matches? (get body "password") (:password_hash account))
                                   row (= (get body "recoveryKey") (:recovery_key row)))
                      (errors/raise! 400 "HandleNotAvailable" "Handle or email is already registered"))
                    (db/execute! conn "UPDATE plc_identities SET status = 'pending', available_at = now(), last_error = NULL
                                       WHERE did = ? AND status = 'failed'" (:did account))
                    (:did account)))))]
    (provision-one! ds settings did)
    (db/transact! ds
      (fn [conn]
        (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" did))]
          (when-not (= "active" (:status account))
            (errors/raise! 503 "RegistrationPending" "Identity registration is pending; retry signup with the same credentials, or sign in after it completes"))
          ;; Activation can finish on another worker before this transaction.
          ;; Do not bypass a password/email/factor change made since reservation.
          (when-not (and (= address (:email account)) (not (:email_auth_factor account)) (not (factors/enabled? conn did))
                         (crypto/password-matches? (get body "password") (:password_hash account)))
            (errors/raise! 401 "AuthenticationRequired" "Account credentials changed; sign in to continue"))
          (merge (public-account account) (when issue-session? (auth/issue! conn settings did nil))
                 {:didDoc (did-document conn settings account (:public_key (repo/state conn did)))}))))))

(defn- create-account! [ds settings body issue-session?]
  (when-not (:signup-enabled settings) (errors/raise! 403 "SignupDisabled" "Account registration is disabled"))
  (when (some #(contains? body %) ["did" "plcOp" "verificationCode" "verificationPhone"])
    (errors/raise! 400 "InvalidRequest" "Account imports and phone verification are not implemented"))
  (when (and (not= :plc (:did-method settings)) (contains? body "recoveryKey"))
    (errors/invalid! "Recovery keys require PLC signup"))
  (let [handle (str/lower-case (request/string! (get body "handle") "handle"))
        address (str/lower-case (request/string! (get body "email") "email"))
        suffix (str "." (:user-domain settings))]
    (when (= handle (:hostname settings)) (errors/raise! 400 "HandleNotAvailable" "The service hostname is reserved"))
    (when-not (syntax/handle? handle) (errors/raise! 400 "InvalidHandle" "Invalid handle"))
    (when-not (and (str/ends-with? handle suffix)
                   (not (str/includes? (subs handle 0 (- (count handle) (count suffix))) ".")))
      (errors/raise! 400 "UnsupportedDomain" "Handle must be a direct child of the configured user domain"))
    (when-not (email/address? address) (errors/invalid! "Invalid email address"))
    (let [hash (password! (get body "password")) did (str "did:web:" handle)]
      (if (= :plc (:did-method settings))
        (create-plc! ds settings body handle address hash issue-session?)
        (try
          (db/transact!
           ds
           (fn [conn]
             (db/execute! conn "INSERT INTO accounts(did, handle, email, password_hash) VALUES (?, ?, ?, ?)" did handle address hash)
             (handles/reserve! conn did handle)
             (invites/consume! conn settings (get body "inviteCode") did)
             (repo/initialize! conn settings did handle)
             (let [account (resolve-account conn did)]
               (when (:email-enabled settings) (issue-email! conn account "confirm-email"))
               (merge (public-account account) (when issue-session? (auth/issue! conn settings did nil))
                      {:didDoc (did-document settings account (:public_key (repo/state conn did)))}))))
          (catch java.sql.SQLException e
            (if (= "23505" (.getSQLState e))
              (errors/raise! 400 "HandleNotAvailable" "Handle or email is already registered") (throw e))))))))

(defn create! [ds settings body]
  (create-account! ds settings body true))

(defn register!
  "Create an account without minting legacy bearer credentials. Browser/OAuth
  callers must separately authenticate and establish their protected session."
  [ds settings body]
  (create-account! ds settings body false))

(def dummy-password (delay (crypto/password-hash (crypto/token))))
(declare consume-token! require-email!)
(defn login! [ds settings body]
  (let [identifier (str/lower-case (request/string! (get body "identifier") "identifier"))
        password (get body "password")
        result
    (db/transact!
     ds
     (fn [conn]
       (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? OR handle = ? OR email = ? FOR UPDATE"
                                     identifier identifier identifier))
             matches? (crypto/password-matches? password (or (:password_hash account) @dummy-password))
             app-password (when (and account (not matches?)) (app-passwords/find-password conn settings (:did account) password))]
         (when-not (and account (or matches? app-password)
                        (or (= "active" (:status account)) (and matches? (= "deactivated" (:status account)))))
           (errors/raise! 401 "AuthenticationRequired" "Invalid identifier or password"))
         (cond
           (and matches? (factors/enabled? conn (:did account)))
           (if-not (contains? body "authFactorToken")
             {:error "AuthFactorTokenRequired" :status 401}
             (let [factor (factors/verify! conn settings (:did account) (get body "authFactorToken"))]
               (if (:error factor) factor
                 (merge (public-account account) (auth/issue! conn settings (:did account) nil)))))

           (and matches? (:email_auth_factor account) (not (contains? body "authFactorToken")))
           (do (require-email! settings) (issue-email! conn account "sign-in") ::factor-required)

           :else
           (do
             (when (and matches? (:email_auth_factor account))
               (consume-token! conn "sign-in" (get body "authFactorToken") (:did account) (:email account)))
             (merge (public-account account) (auth/issue! conn settings (:did account) nil (:id app-password))))))))]
    ;; Commit challenges and failed TOTP attempt accounting before raising.
    (cond
      (= ::factor-required result) (errors/raise! 401 "AuthFactorTokenRequired" "Check your email for a sign-in token")
      (:error result) (errors/raise! (:status result) (:error result) "An authenticator or recovery code is required")
      :else result)))

(defn require-email! [settings]
  (when-not (:email-enabled settings) (errors/raise! 503 "EmailUnavailable" "Email delivery is not configured")))
(defn request-confirmation! [conn settings account]
  (permissions/account! account "email" "manage")
  (require-email! settings) (issue-email! conn account "confirm-email"))
(defn request-reset! [ds settings body]
  (require-email! settings)
  (let [address (str/lower-case (request/string! (get body "email") "email"))]
    (db/transact! ds
      (fn [conn]
        (when-let [account (first (db/query conn "SELECT * FROM accounts WHERE email = ? AND status IN ('active', 'deactivated') FOR UPDATE" address))]
          (issue-email! conn account "reset-password")))))
  ;; Same result for known and unknown email addresses.
  nil)
(defn consume-token! [conn purpose token did email]
  (request/string! token "token")
  (let [row (first (db/query conn "DELETE FROM account_tokens WHERE token_hash = ? AND purpose = ?
                                  AND expires_at > now() AND (?::text IS NULL OR did = ?)
                                  AND (?::text IS NULL OR email = ?) RETURNING did, email"
                            (crypto/digest-token token) purpose did did email email))]
    (or row (errors/raise! 400 "InvalidToken" "Invalid or expired email token"))))
(defn confirm! [conn account body]
  (permissions/account! account "email" "manage")
  (let [address (get body "email")]
    (when-not (= address (:email account)) (errors/invalid! "Email does not match account"))
    (consume-token! conn "confirm-email" (get body "token") (:did account) address)
    (db/execute! conn "UPDATE accounts SET email_confirmed = true WHERE did = ?" (:did account))))
(defn reset-password! [ds body]
  (let [hash (password! (get body "password"))]
    (db/transact!
     ds
     (fn [conn]
       ;; Lock account before token/session writes, using the same order as login.
       (let [digest (crypto/digest-token (request/string! (get body "token") "token"))
             account (first (db/query conn "SELECT a.did FROM accounts a JOIN account_tokens t ON t.did = a.did
                                            WHERE t.token_hash = ? FOR UPDATE OF a" digest))]
         (when-not account (errors/raise! 400 "InvalidToken" "Invalid or expired email token"))
         (let [row (consume-token! conn "reset-password" (get body "token") (:did account) nil)]
           (db/execute! conn "UPDATE accounts SET password_hash = ? WHERE did = ?" hash (:did row))
           (db/execute! conn "DELETE FROM app_passwords WHERE did = ?" (:did row))
           (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ?" (:did row))))))))

(defn deactivate! [conn account body]
  (auth/require-primary! account)
  (let [delete-after (get body "deleteAfter")]
    (when (and (contains? body "deleteAfter") (not (formats/datetime? delete-after)))
      (errors/invalid! "deleteAfter must be a valid datetime"))
    (db/execute! conn "UPDATE accounts SET status = 'deactivated', delete_after = ? WHERE did = ?" delete-after (:did account))
    (when (not= "deactivated" (:status account)) (events/account! conn (:did account) "deactivated"))))

(defn activate! [conn account]
  (auth/require-primary! account)
  (when (seq (db/query conn "SELECT 1 FROM account_imports WHERE did = ?" (:did account)))
    (errors/raise! 400 "MigrationIncomplete" "Repository import and destination activation are not yet complete"))
  (when (= "deactivated" (:status account))
    (db/execute! conn "UPDATE accounts SET status = 'active', delete_after = NULL WHERE did = ?" (:did account))
    (events/account! conn (:did account) "active")
    (events/sync! conn (:did account))))

(defn request-deletion! [conn settings account]
  (auth/require-primary! account)
  (require-email! settings)
  (issue-email! conn account "delete-account"))

(defn delete! [ds body]
  ;; The upstream endpoint authenticates with both primary password and a
  ;; one-use email token; a Bearer session is not required for the final step.
  (let [did (request/string! (get body "did") "did")]
    (db/transact! ds
      (fn [conn]
        (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? AND status <> 'deleted' FOR UPDATE" did))]
          (when-not (and account (crypto/password-matches? (get body "password") (:password_hash account)))
            (errors/raise! 401 "AuthenticationRequired" "Invalid DID or password"))
          (consume-token! conn "delete-account" (get body "token") did (:email account))
          (when (seq (db/query conn "SELECT 1 FROM handle_updates WHERE did = ?" did))
            (errors/raise! 409 "IdentityUpdatePending" "Finish the pending identity update before deleting this account"))
          (db/execute! conn "UPDATE handle_reservations SET permanent = true WHERE did = ?" did)
          (db/execute! conn "INSERT INTO blob_delete_jobs(did, cid, object_bucket, object_key)
                            SELECT did, cid, object_bucket, object_key FROM blobs WHERE did = ? AND storage_backend = 's3'
                            ON CONFLICT (object_bucket, object_key) DO UPDATE
                            SET did = excluded.did, cid = excluded.cid, status = 'pending', attempts = 0,
                                available_at = now(), last_error = NULL" did)
          (doseq [table ["sessions" "app_passwords" "account_tokens" "account_totp" "webauthn_challenges" "account_webauthn_users" "blobs" "account_imports" "plc_identities" "repositories"]]
            (db/execute! conn (str "DELETE FROM " table " WHERE did = ?") did))
          (db/execute! conn "DELETE FROM email_outbox WHERE payload->>'to' = ?" (:email account))
          (db/execute! conn "DELETE FROM repo_events WHERE did = ? AND event_type IN ('commit', 'sync')" did)
          ;; Reserve the DID/handle permanently while erasing credentials/email.
          (db/execute! conn "UPDATE accounts SET status = 'deleted', email = NULL, password_hash = NULL,
                              email_confirmed = false, email_auth_factor = false, delete_after = NULL WHERE did = ?" did)
          (events/account! conn did "deleted"))))))

(defn request-email-update! [conn settings account]
  (auth/require-management! account :account "email")
  (when (:email_confirmed account)
    (require-email! settings)
    (issue-email! conn account "update-email"))
  {:tokenRequired (boolean (:email_confirmed account))})

(defn update-email! [conn settings account body]
  (auth/require-management! account :account "email")
  (when (and (:oauth-scope account) (contains? body "emailAuthFactor")) (permissions/denied!))
  (require-email! settings)
  (let [address (str/lower-case (request/string! (get body "email") "email"))
        changed? (not= address (:email account))
        factor (get body "emailAuthFactor" (if changed? false (:email_auth_factor account)))]
    (when-not (email/address? address) (errors/invalid! "Invalid email address"))
    (when-not (boolean? factor) (errors/invalid! "emailAuthFactor must be a boolean"))
    (when (and factor (factors/enabled? conn (:did account)))
      (errors/invalid! "Disable the authenticator before enabling the email factor"))
    (when (and factor (or changed? (not (:email_confirmed account))))
      (errors/invalid! "Confirm the email address before enabling email authentication"))
    (when (:email_confirmed account)
      (when-not (contains? body "token") (errors/raise! 400 "TokenRequired" "An email-update token is required"))
      (consume-token! conn "update-email" (get body "token") (:did account) (:email account)))
    (try
      (db/execute! conn "UPDATE accounts SET email = ?, email_confirmed = ?, email_auth_factor = ? WHERE did = ?"
                   address (and (not changed?) (:email_confirmed account)) factor (:did account))
      (catch java.sql.SQLException e
        (if (= "23505" (.getSQLState e)) (errors/invalid! "Email is unavailable") (throw e))))
    (when (or changed? (not= factor (:email_auth_factor account)))
      (db/execute! conn "DELETE FROM account_tokens WHERE did = ?" (:did account))
      (db/execute! conn "DELETE FROM email_outbox WHERE payload->>'to' = ?" (:email account))
      (if (:oauth-scope account)
        (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ?" (:did account))
        (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ? AND id <> ?" (:did account) (:session-id account))))
    (when changed?
      (issue-email! conn (assoc account :email address :email_confirmed false :email_auth_factor false) "confirm-email"))))
