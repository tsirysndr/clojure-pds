(ns pds.admin-accounts
  "Administrative account recovery. HTTP callers must authenticate the operator
  before entering these transactions; user credentials do not authorize them."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.email :as email]
            [pds.errors :as errors]
            [pds.protocol.syntax :as syntax]
            [pds.request :as request]))

(defn- lock! [conn identifier allow-deleted?]
  (when (.getAutoCommit ^java.sql.Connection conn)
    (throw (ex-info "Administrative account changes require a transaction" {})))
  (when-not (syntax/at-identifier? identifier) (errors/invalid! "Invalid account identifier"))
  (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? OR handle = ? FOR UPDATE"
                                identifier (str/lower-case identifier)))]
    (when (or (nil? account) (and (not allow-deleted?) (= "deleted" (:status account))))
      (errors/raise! 400 "AccountNotFound" "Account was not found"))
    (when (= "provisioning" (:status account))
      (errors/raise! 409 "RegistrationPending" "Finish identity registration before changing this account"))
    account))

(defn- invalidate! [conn account]
  (let [did (:did account)]
    ;; Epoch advancement fences OAuth grants, consent, codes and browser proofs.
    ;; Preserve OAuth replay evidence and enrolled TOTP/passkey credentials.
    (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ?" did)
    (db/execute! conn "DELETE FROM app_passwords WHERE did = ?" did)
    (db/execute! conn "DELETE FROM account_tokens WHERE did = ?" did)
    (db/execute! conn "DELETE FROM webauthn_challenges WHERE did = ?" did)
    (db/execute! conn "DELETE FROM email_outbox WHERE payload->>'to' = ?" (:email account))))

(defn update-password! [conn body]
  (when-not (syntax/did? (get body "did")) (errors/invalid! "Invalid DID"))
  (let [hash (accounts/password! (get body "password"))
        account (lock! conn (get body "did") false)]
    (db/execute! conn "UPDATE accounts SET password_hash = ?, oauth_epoch = oauth_epoch + 1 WHERE did = ?"
                 hash (:did account))
    (invalidate! conn account)))

(defn update-email! [conn body]
  (let [address (str/lower-case (request/string! (get body "email") "email"))]
    (when-not (email/address? address) (errors/invalid! "Invalid email address"))
    (let [account (lock! conn (get body "account") false)]
      (try
        (db/execute! conn "UPDATE accounts SET email = ?, email_confirmed = false, email_auth_factor = false,
                            oauth_epoch = oauth_epoch + 1 WHERE did = ?" address (:did account))
        (catch java.sql.SQLException e
          (if (= "23505" (.getSQLState e)) (errors/invalid! "Email is unavailable") (throw e))))
      (invalidate! conn account))))

(defn send-email! [conn settings body]
  (when-not (:email-enabled settings) (errors/raise! 503 "EmailUnavailable" "Email delivery is not configured"))
  (let [content (get body "content") subject (get body "subject" "Message via your PDS")]
    (when-not (and (string? content) (<= 1 (count content) 16000))
      (errors/invalid! "Content must contain 1 to 16000 characters"))
    (when-not (and (string? subject) (<= 1 (count subject) 200) (not (re-find #"[\r\n]" subject)))
      (errors/invalid! "Invalid email subject"))
    (let [account (lock! conn (get body "recipientDid") false)]
      (when-not (:email account) (errors/invalid! "Account has no email address"))
      (email/enqueue! conn {:to (:email account) :subject subject :text content})
      {:sent true})))

(defn delete! [conn body]
  (when-not (syntax/did? (get body "did")) (errors/invalid! "Invalid DID"))
  (let [account (lock! conn (get body "did") true)]
    ;; Retained tombstones make a repeated operator request idempotent without
    ;; emitting another account event or scheduling duplicate object deletions.
    (when-not (= "deleted" (:status account)) (accounts/erase-account! conn account))))

(defn recovery-reference? [value]
  (and (string? value) (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}" value))))
(defn- factor-summary [conn account]
  (let [did (:did account) totp (first (db/query conn "SELECT confirmed FROM account_totp WHERE did = ?" did))]
    {:emailFactor (boolean (:email_auth_factor account))
     :totpEnrolled (boolean totp) :totpConfirmed (boolean (:confirmed totp))
     :recoveryCodes (:n (first (db/query conn "SELECT count(*) AS n FROM account_recovery_codes WHERE did = ?" did)))
     :passkeys (:n (first (db/query conn "SELECT count(*) AS n FROM account_passkeys WHERE did = ?" did)))}))
(defn- recovery-receipt [row]
  {:did (:did row) :state "completed" :previousVersion (:previous_version row)
   :securityVersion (:security_version row) :reference (:reference row)
   :databaseRole (:database_role row)
   :removedFactors (json/read-str (:removed_factors row) :key-fn keyword)
   :completedAt (str (.toInstant ^java.sql.Timestamp (:completed_at row)))})
(defn recovery-status! [conn did]
  (when-not (syntax/did? did) (errors/invalid! "Invalid DID"))
  (let [account (lock! conn did false)
        recent (first (db/query conn "SELECT *, removed_factors::text AS removed_factors FROM authenticator_recoveries WHERE did = ? ORDER BY previous_version DESC LIMIT 1" did))]
    (cond-> {:did did :status (:status account) :securityVersion (:oauth_epoch account)
             :factors (factor-summary conn account)}
      recent (assoc :lastRecovery (recovery-receipt recent)))))
(defn recover-authenticators!
  "Local operator only: atomically replace the password and erase lost factors.
  The expected version/reference identifies one operation; retries return its
  credential-free receipt even after later owner enrollment."
  [conn did expected reference password]
  (when-not (and (syntax/did? did) (integer? expected) (<= 0 expected Long/MAX_VALUE)
                 (recovery-reference? reference))
    (errors/invalid! "Expected a DID, nonnegative security version and recovery reference"))
  (let [account (lock! conn did false)]
    (if-let [done (first (db/query conn "SELECT *, removed_factors::text AS removed_factors FROM authenticator_recoveries
                                        WHERE did = ? AND previous_version = ?" did expected))]
      (if (= reference (:reference done)) (recovery-receipt done)
        (errors/raise! 409 "RecoveryAlreadyCompleted" "This security version was recovered under a different reference"))
      (do
        (when-not (= expected (:oauth_epoch account))
          (errors/raise! 409 "SecurityVersionMismatch" "Account security changed; inspect status before recovery"))
        (when (crypto/password-matches? password (:password_hash account))
          (errors/invalid! "Recovery requires a different primary password"))
        (let [hash (accounts/password! password) removed (factor-summary conn account)]
          (db/execute! conn "UPDATE accounts SET password_hash = ?, email_auth_factor = false, oauth_epoch = oauth_epoch + 1 WHERE did = ?" hash did)
          (invalidate! conn account)
          (db/execute! conn "DELETE FROM account_totp WHERE did = ?" did)
          (db/execute! conn "DELETE FROM account_webauthn_users WHERE did = ?" did)
          ;; Browser/OAuth rows retain replay evidence. Epoch checks fence them;
          ;; do not invert browser-session -> account lock order by deleting here.
          (let [version (:oauth_epoch (first (db/query conn "SELECT oauth_epoch FROM accounts WHERE did = ?" did)))
                row (first (db/query conn "INSERT INTO authenticator_recoveries(did, previous_version, security_version, reference, removed_factors)
                                            VALUES (?, ?, ?, ?, ?::jsonb) RETURNING *, removed_factors::text AS removed_factors"
                                     did expected version reference (json/write-str removed)))]
            (recovery-receipt row)))))))
