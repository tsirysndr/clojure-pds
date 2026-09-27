(ns pds.oauth.interaction
  "Durable browser authorization. HTTP cookies, Origin checks and rendering belong
  in the browser adapter; callers must never return browser secrets to clients."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.oauth.client :as client]
            [pds.oauth.client-auth :as client-auth]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.http :as http]
            [pds.oauth.par :as par]
            [pds.oauth.grants :as grants]
            [pds.protocol.codec :as codec]
            [pds.security.factors :as factors]
            [pds.security.browser :as browser-session])
  (:import [java.net URLEncoder]
           [java.security MessageDigest]
           [java.time Instant]))

(def lifetime 600)
(def code-lifetime 60)
(defn- now [] (Instant/ofEpochSecond (dpop/now)))
(defn- token? [value] (and (string? value) (boolean (re-matches #"[A-Za-z0-9_-]{43}" value))))
(defn- invalid! [] (http/fail! "invalid_request" "Invalid or expired browser interaction"))
(defn- live! [row]
  (when-not (.isAfter (.toInstant ^java.sql.Timestamp (:expires_at row)) (now)) (invalid!)))
(defn- equal? [a b]
  (and (string? a) (string? b) (MessageDigest/isEqual (codec/utf8 a) (codec/utf8 b))))
(defn- csrf [row browser]
  (crypto/b64 (crypto/hmac (codec/utf8 browser)
                          (codec/utf8 (str "pds/oauth/interaction/csrf\n" (:interaction_hash row) "\n" (:csrf_nonce row))))))
(defn- snapshot [row]
  (let [value (json/read-str (:snapshot row))]
    {:client-id (get value "client-id") :parameters (get value "parameters")
     :client-binding (into {} (map (fn [[k v]] [(keyword k) v]) (get value "client-binding")))
     :dpop-jkt (get value "dpop-jkt") :permission-sets (get value "permission-sets" {})}))
(defn- load! [conn id browser lock?]
  (when-not (and (token? id) (token? browser)) (invalid!))
  (let [row (first (db/query conn (str "SELECT *, snapshot::text FROM oauth_interactions
                                      WHERE interaction_hash = ? AND expires_at > ? AND completed_at IS NULL"
                                     (when lock? " FOR UPDATE"))
                             (crypto/digest-token id) (now)))]
    (when-not (and row (equal? (:browser_hash row) (crypto/digest-token browser))) (invalid!))
    (live! row)
    row))
(defn- check-csrf! [row browser value]
  (when-not (and (token? value) (equal? (csrf row browser) value)) (invalid!)))
(defn- view [row browser]
  (let [request (snapshot row)]
    (merge {:client-id (:client-id request) :parameters (:parameters request)
            :did (:did row) :csrf (csrf row browser)}
           (grants/describe (get-in request [:parameters "scope"]) (:permission-sets request)))))

(defn start!
  "Consume a PAR URI and create an independent ten-minute browser interaction.
  The returned browser secret belongs only in an HttpOnly same-site cookie."
  [ds client-id request-uri]
  (let [id (crypto/token) browser (crypto/token)]
    (db/transact! ds
      (fn [conn]
        (let [request (par/claim! conn client-id request-uri) timestamp (now)]
          (db/execute! conn "DELETE FROM oauth_interactions WHERE interaction_hash IN
                             (SELECT interaction_hash FROM oauth_interactions WHERE expires_at <= ?
                              ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)" timestamp)
          (db/execute! conn "INSERT INTO oauth_interactions(interaction_hash, browser_hash, csrf_nonce, snapshot, created_at, expires_at)
                             VALUES (?, ?, ?, ?::jsonb, ?, ?)"
                       (crypto/digest-token id) (crypto/digest-token browser) (crypto/token) (json/write-str request)
                       timestamp (.plusSeconds timestamp lifetime))
          {:id id :browser browser})))))

(defn inspect! [ds id browser]
  (with-open [conn (db/connection ds)] (view (load! conn id browser false) browser)))

(defn- hint-matches? [request account]
  (let [hint (get-in request [:parameters "login_hint"])]
    (or (str/blank? hint) (contains? #{(:did account) (:handle account) (:email account)} (str/lower-case hint)))))

(defn authenticate!
  "Verify primary credentials and any email factor without issuing a legacy JWT.
  Challenges commit before returning :factor-required. Approval is a separate POST
  with a rotated CSRF token; account switching requires a new interaction."
  [ds settings id browser csrf-token body]
  (db/transact! ds
    (fn [conn]
      (let [row (load! conn id browser true)
            _ (check-csrf! row browser csrf-token)
            _ (when (:did row) (invalid!))
            identifier (get body "identifier") password (get body "password")
            _ (when-not (and (string? identifier) (<= 1 (count identifier) 2048)
                             (string? password) (<= 1 (count password) 1024))
                (errors/raise! 401 "AuthenticationRequired" "Invalid identifier or password"))
            identifier (str/lower-case identifier)
            account (first (db/query conn "SELECT * FROM accounts WHERE did = ? OR handle = ? OR email = ? FOR UPDATE"
                                    identifier identifier identifier))
            matches? (crypto/password-matches? password (or (:password_hash account) @accounts/dummy-password))]
        (when-not (and matches? (= "active" (:status account)) (hint-matches? (snapshot row) account))
          (errors/raise! 401 "AuthenticationRequired" "Invalid identifier or password"))
        (live! row)
        (let [totp? (factors/enabled? conn (:did account))
              factor (cond
                       (and (or totp? (:email_auth_factor account)) (not (contains? body "authFactorToken")))
                       (do (when-not totp? (accounts/require-email! settings) (accounts/issue-email! conn account "sign-in"))
                           {:factor-required true :factor-type (if totp? :totp :email)})
                       totp? (factors/verify! conn settings (:did account) (get body "authFactorToken"))
                       (:email_auth_factor account)
                       (do (accounts/consume-token! conn "sign-in" (get body "authFactorToken") (:did account) (:email account)) nil))]
          ;; Return failed TOTP proofs so PostgreSQL commits the attempt budget.
          (if (or (:factor-required factor) (:error factor)) factor
            (do
              (db/execute! conn "UPDATE oauth_interactions SET did = ?, account_epoch = ?, csrf_nonce = ? WHERE interaction_hash = ?"
                           (:did account) (:oauth_epoch account) (crypto/token) (:interaction_hash row))
              (view (load! conn id browser false) browser))))))))

(defn authenticate-browser!
  "Bind a recent owner session to this interaction. Both independent CSRF tokens
  are required. Consent remains a separate operation with a rotated flow CSRF."
  [ds id browser csrf-token account-token account-csrf]
  (db/transact! ds
    (fn [conn]
      (let [row (load! conn id browser true)
            _ (check-csrf! row browser csrf-token)
            _ (when (:did row) (invalid!))
            owner (browser-session/owner! conn account-token account-csrf)
            account (first (db/query conn "SELECT * FROM accounts WHERE did = ?" (:did owner)))
            request (snapshot row)]
        (when-not (hint-matches? request account)
          (http/fail! "access_denied" "Sign in with the account requested by this application"))
        (when (and (= "login" (get-in request [:parameters "prompt"]))
                   (.isBefore (.toInstant ^java.sql.Timestamp (:authenticated-at owner))
                              (.toInstant ^java.sql.Timestamp (:created_at row))))
          (http/fail! "login_required" "Sign in again to continue"))
        (live! row)
        (db/execute! conn "UPDATE oauth_interactions SET did = ?, account_epoch = ?, csrf_nonce = ? WHERE interaction_hash = ?"
                     (:did owner) (:account-epoch owner) (crypto/token) (:interaction_hash row))
        (view (load! conn id browser false) browser)))))

(defn- callback [settings request result]
  ;; Append to the original registered URI without reserializing its path/query.
  (let [uri (get-in request [:parameters "redirect_uri"])
        fields (merge {"state" (get-in request [:parameters "state"]) "iss" (:public-url settings)} result)]
    (str uri (if (str/includes? uri "?") (if (or (str/ends-with? uri "?") (str/ends-with? uri "&")) "" "&") "?")
         (str/join "&" (map (fn [[k v]] (str (URLEncoder/encode k "UTF-8") "=" (URLEncoder/encode v "UTF-8"))) fields)))))

(defn decide!
  "Approve or deny once. Refresh metadata outside the transaction, then lock and
  recheck interaction/account state before atomically issuing a bound code."
  [ds resolver settings id browser csrf-token approve?]
  (when-not (boolean? approve?) (invalid!))
  (let [request (with-open [conn (db/connection ds)]
                  (let [row (load! conn id browser false)]
                    (check-csrf! row browser csrf-token) (snapshot row)))
        resolved (client/resolve! resolver (:client-id request))]
    (par/parameters! resolved (:parameters request))
    (when-not (client-auth/binding-current? resolved (:client-binding request)) (client-auth/invalid!))
    (db/transact! ds
      (fn [conn]
        (let [row (load! conn id browser true) _ (check-csrf! row browser csrf-token)
              timestamp (now)
              result
              (if approve?
                (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" (:did row)))
                      code (crypto/token)]
                  (when-not (and account (= "active" (:status account))
                                 (= (:oauth_epoch account) (:account_epoch row)) (hint-matches? request account))
                    (http/fail! "access_denied" "Account authentication is no longer valid"))
                  (live! row)
                  (db/execute! conn "INSERT INTO oauth_codes(code_hash, did, account_epoch, snapshot, created_at, expires_at)
                                     VALUES (?, ?, ?, ?::jsonb, ?, ?)"
                               (crypto/digest-token code) (:did row) (:account_epoch row) (json/write-str request)
                               timestamp (.plusSeconds timestamp code-lifetime))
                  {"code" code})
                {"error" "access_denied"})]
          (db/execute! conn "UPDATE oauth_interactions SET completed_at = ? WHERE interaction_hash = ?" timestamp (:interaction_hash row))
          {:location (callback settings request result)})))))
