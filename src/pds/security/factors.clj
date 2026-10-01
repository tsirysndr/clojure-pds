(ns pds.security.factors
  "Transactional optional TOTP and recovery proofs. Management callers must first
  verify a primary credential and CSRF; these functions are not HTTP endpoints.
  Invalid proofs return errors so attempt accounting can commit before responding."
  (:require [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.security.totp :as totp])
  (:import [java.time Instant]
           [java.util Locale]))

(defn now [] (.getEpochSecond (Instant/now)))
(defn- timestamp [] (Instant/ofEpochSecond (now)))
(defn- purpose [did] (str "pds/totp/v1/" did))
(defn- lock! [conn did]
  (when (.getAutoCommit ^java.sql.Connection conn) (throw (ex-info "Factor operations require a transaction" {})))
  (or (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" did))
      (errors/raise! 401 "AuthenticationRequired" "Account is unavailable")))
(defn enabled? [conn did]
  (boolean (seq (db/query conn "SELECT 1 FROM account_totp WHERE did = ? AND confirmed" did))))
(defn- row [conn did] (first (db/query conn "SELECT * FROM account_totp WHERE did = ?" did)))
(defn status
  "The factor's state, for presentation. An unconfirmed enrollment counts as
  pending: the account still authenticates with its password alone until the
  owner proves possession of the secret."
  [conn did]
  (let [factor (row conn did)]
    {:state (cond (nil? factor) "disabled" (:confirmed factor) "enabled" :else "pending")
     :recovery-remaining (:n (first (db/query conn "SELECT count(*) AS n FROM account_recovery_codes WHERE did = ?" did)))}))
(defn- invalidate! [conn did]
  (db/execute! conn "UPDATE accounts SET oauth_epoch = oauth_epoch + 1 WHERE did = ?" did)
  (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ?" did)
  (db/execute! conn "DELETE FROM app_passwords WHERE did = ?" did))
(defn- recovery-digest [did value] (crypto/digest-token (str "pds/recovery/v1/" did "/" value)))
(defn- recovery-codes! [conn did]
  (let [codes (vec (repeatedly 10 #(str/join "-" (map (partial apply str) (partition 4 (totp/encoded-secret (crypto/random-bytes 20)))))))]
    (db/execute! conn "DELETE FROM account_recovery_codes WHERE did = ?" did)
    (doseq [code codes]
      (db/execute! conn "INSERT INTO account_recovery_codes(did, code_hash) VALUES (?, ?)" did (recovery-digest did code)))
    codes))

(defn begin!
  "Stage a ten-minute enrollment. The account remains unchanged until the owner
  proves possession of the secret. Requires recent primary authentication at the
  management boundary, including any existing factor; never accept a bare DID
  from an unauthenticated HTTP request."
  [conn settings did]
  (let [account (lock! conn did) secret (totp/secret) time (timestamp)]
    (when-not (= "active" (:status account)) (errors/raise! 403 "AccountUnavailable" "Account is not active"))
    (when (:email_auth_factor account)
      (errors/raise! 400 "EmailFactorEnabled" "Disable the email factor before enabling an authenticator"))
    (when (enabled? conn did) (errors/raise! 400 "TotpAlreadyEnabled" "An authenticator is already enabled"))
    (db/execute! conn "INSERT INTO account_totp(did, sealed_secret, enrollment_epoch, enrollment_expires_at, attempt_window)
                       VALUES (?, ?, ?, ?, ?) ON CONFLICT (did) DO UPDATE SET sealed_secret = excluded.sealed_secret,
                       enrollment_epoch = excluded.enrollment_epoch, enrollment_expires_at = excluded.enrollment_expires_at,
                       failed_attempts = 0, attempt_window = excluded.attempt_window"
                 did (crypto/seal (:master-key settings) (purpose did) secret) (:oauth_epoch account) (.plusSeconds time 600) time)
    {:secret (totp/encoded-secret secret)
     :uri (totp/provisioning-uri secret (:hostname settings) (:handle account)) :expires-in 600}))

(defn- verify-row! [conn settings account factor supplied recovery?]
  (let [time (timestamp) window (.toInstant ^java.sql.Timestamp (:attempt_window factor))
        new-window? (not (.isBefore time (.plusSeconds window 300)))
        failures (if new-window? 0 (:failed_attempts factor)) did (:did account)]
    (if (>= failures 5)
      {:error "RateLimitExceeded" :status 429 :retry-after (max 1 (- (+ (.getEpochSecond window) 300) (now)))}
      (let [secret (crypto/unseal (:master-key settings) (purpose did) (:sealed_secret factor))
            step (totp/matching-step secret (now) supplied)
            fresh? (and step (or (nil? (:last_step factor)) (> step (:last_step factor))))
            recovery (when (and recovery? (string? supplied) (re-matches #"(?i)[A-Z2-7]{4}(?:-[A-Z2-7]{4}){7}" supplied))
                       (pos? (db/execute! conn "DELETE FROM account_recovery_codes WHERE did = ? AND code_hash = ?"
                                          did (recovery-digest did (.toUpperCase ^String supplied Locale/ROOT)))))
            accepted? (or fresh? recovery)]
        (db/execute! conn "UPDATE account_totp SET failed_attempts = ?, attempt_window = ?, last_step = ? WHERE did = ?"
                     (if accepted? 0 (inc failures)) (if new-window? time window) (if fresh? step (:last_step factor)) did)
        (if accepted? {:valid? true} {:error "InvalidToken" :status 401})))))

(defn verify!
  "Consume an enabled factor's TOTP or recovery code with persistent throttling.
  The caller must commit returned errors; throwing within the transaction would
  roll back failed-attempt accounting. A missing factor is not a valid proof."
  [conn settings did supplied]
  (let [account (lock! conn did) factor (row conn did)]
    (if (and factor (:confirmed factor))
      (verify-row! conn settings account factor supplied true)
      {:error "InvalidToken" :status 401})))

(defn confirm! [conn settings did supplied]
  (let [account (lock! conn did) factor (row conn did)]
    (when-not (and factor (not (:confirmed factor)) (= "active" (:status account))
                   (not (:email_auth_factor account)) (= (:oauth_epoch account) (:enrollment_epoch factor))
                   (.isAfter (.toInstant ^java.sql.Timestamp (:enrollment_expires_at factor)) (timestamp)))
      (errors/raise! 400 "InvalidEnrollment" "Authenticator enrollment expired or account credentials changed"))
    (let [result (verify-row! conn settings account factor supplied false)]
      (if (:valid? result)
        (do (db/execute! conn "UPDATE account_totp SET confirmed = true WHERE did = ?" did)
            (invalidate! conn did)
            {:recovery-codes (recovery-codes! conn did)})
        result))))

(defn regenerate!
  "Replace the recovery codes. Requires a current proof for the same reason
  disabling does: the codes are themselves a way past the factor."
  [conn settings did supplied]
  (let [result (verify! conn settings did supplied)]
    (if (:valid? result)
      {:recovery-codes (recovery-codes! conn did)}
      result)))

(defn disable!
  "Management must separately require recent primary authentication. A current
  TOTP or one-use recovery code is additionally required to remove the factor."
  [conn settings did supplied]
  (let [result (verify! conn settings did supplied)]
    (if (:valid? result)
      (do (db/execute! conn "DELETE FROM account_totp WHERE did = ?" did)
          (invalidate! conn did) {:disabled true})
      result)))
