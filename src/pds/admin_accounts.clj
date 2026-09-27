(ns pds.admin-accounts
  "Administrative account recovery. HTTP callers must authenticate the operator
  before entering these transactions; user credentials do not authorize them."
  (:require [clojure.string :as str]
            [pds.accounts :as accounts]
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

(defn delete! [conn body]
  (when-not (syntax/did? (get body "did")) (errors/invalid! "Invalid DID"))
  (let [account (lock! conn (get body "did") true)]
    ;; Retained tombstones make a repeated operator request idempotent without
    ;; emitting another account event or scheduling duplicate object deletions.
    (when-not (= "deleted" (:status account)) (accounts/erase-account! conn account))))
