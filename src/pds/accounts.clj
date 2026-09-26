(ns pds.accounts
  (:require [clojure.string :as str]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.email :as email]
            [pds.errors :as errors]
            [pds.protocol.syntax :as syntax]
            [pds.repo :as repo]
            [pds.request :as request])
  (:import [java.net URI]
           [java.time Instant]))

(defn settings [env]
  (let [domain (get env "PDS_USER_DOMAIN" (get env "PDS_HOSTNAME" "pds.localhost"))
        url (get env "PDS_PUBLIC_URL" "http://localhost:3000")
        uri (try (URI/create url) (catch Exception _ nil))
        signup (get env "PDS_ENABLE_SIGNUP" "false")]
    (when-not (syntax/handle? domain) (throw (ex-info "PDS_USER_DOMAIN must be a DNS domain" {})))
    (when-not (#{"true" "false"} signup) (throw (ex-info "PDS_ENABLE_SIGNUP must be true or false" {})))
    (when-not (and uri (.getHost uri) (nil? (.getUserInfo uri)) (nil? (.getQuery uri)) (nil? (.getFragment uri))
                   (#{"" "/"} (.getPath uri))
                   (or (= "https" (.getScheme uri))
                       (and (= "http" (.getScheme uri)) (#{"localhost" "127.0.0.1"} (.getHost uri)))))
      (throw (ex-info "PDS_PUBLIC_URL must be an HTTPS origin (loopback HTTP allowed)" {})))
    {:user-domain (str/lower-case domain) :public-url (str/replace url #"/$" "") :signup-enabled (= "true" signup)}))

(defn public-account [account]
  {:did (:did account) :handle (:handle account) :email (:email account)
   :emailConfirmed (:email_confirmed account) :active (= "active" (:status account))})
(defn did-document [settings account public-key]
  {:id (:did account) :alsoKnownAs [(str "at://" (:handle account))]
   :verificationMethod [{:id (str (:did account) "#atproto") :type "Multikey"
                         :controller (:did account) :publicKeyMultibase (crypto/multikey "ES256" public-key)}]
   :service [{:id (str (:did account) "#atproto_pds") :type "AtprotoPersonalDataServer"
              :serviceEndpoint (:public-url settings)}]})
(defn resolve-account [conn identifier]
  (or (first (db/query conn "SELECT * FROM accounts WHERE (did = ? OR handle = ?) AND status = 'active'"
                      identifier (str/lower-case (request/string! identifier "repo"))))
      (errors/raise! 400 "RepoNotFound" "Account was not found")))
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
          subject (case purpose "confirm-email" "Confirm your PDS email" "reset-password" "Reset your PDS password" "delete-account" "Confirm account deletion")]
      (db/execute! conn "DELETE FROM account_tokens WHERE did = ? AND purpose = ?" (:did account) purpose)
      (db/execute! conn "INSERT INTO account_tokens(token_hash, did, purpose, email, expires_at) VALUES (?, ?, ?, ?, ?)"
                   (crypto/digest-token token) (:did account) purpose (:email account) (.plusSeconds (Instant/now) 1800))
      (email/enqueue! conn {:to (:email account) :subject subject
                           :text (str subject ".\n\nYour token is: " token "\n\nIt expires in 30 minutes. If you did not request this, ignore this email.")}))))

(defn create! [ds settings body]
  (when-not (:signup-enabled settings) (errors/raise! 403 "SignupDisabled" "Account registration is disabled"))
  (when (some #(contains? body %) ["did" "plcOp" "recoveryKey" "inviteCode" "verificationCode" "verificationPhone"])
    (errors/raise! 400 "InvalidRequest" "Account imports, invites, and PLC provisioning are not implemented"))
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
      (try
        (db/transact!
         ds
         (fn [conn]
           (db/execute! conn "INSERT INTO accounts(did, handle, email, password_hash) VALUES (?, ?, ?, ?)" did handle address hash)
           (repo/initialize! conn settings did)
           (let [account (resolve-account conn did)]
             (when (:email-enabled settings) (issue-email! conn account "confirm-email"))
             (merge (public-account account) (auth/issue! conn settings did nil)
                    {:didDoc (did-document settings account (:public_key (repo/state conn did)))}))))
        (catch java.sql.SQLException e
          (if (= "23505" (.getSQLState e))
            (errors/raise! 400 "HandleNotAvailable" "Handle or email is already registered") (throw e)))))))

(def dummy-password (delay (crypto/password-hash (crypto/token))))
(defn login! [ds settings body]
  (let [identifier (str/lower-case (request/string! (get body "identifier") "identifier"))
        password (get body "password")]
    (db/transact!
     ds
     (fn [conn]
       (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? OR handle = ? OR email = ? FOR UPDATE"
                                     identifier identifier identifier))
             matches? (crypto/password-matches? password (or (:password_hash account) @dummy-password))]
         (when-not (and account matches? (= "active" (:status account)))
           (errors/raise! 401 "AuthenticationRequired" "Invalid identifier or password"))
         (merge (public-account account) (auth/issue! conn settings (:did account) nil)))))))

(defn require-email! [settings]
  (when-not (:email-enabled settings) (errors/raise! 503 "EmailUnavailable" "Email delivery is not configured")))
(defn request-confirmation! [conn settings account]
  (require-email! settings) (issue-email! conn account "confirm-email"))
(defn request-reset! [ds settings body]
  (require-email! settings)
  (let [address (str/lower-case (request/string! (get body "email") "email"))]
    (db/transact! ds
      (fn [conn]
        (when-let [account (first (db/query conn "SELECT * FROM accounts WHERE email = ? AND status = 'active' FOR UPDATE" address))]
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
           (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ?" (:did row))))))))
