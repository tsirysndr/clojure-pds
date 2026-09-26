(ns pds.oauth.tokens
  "Opaque OAuth grants. Account -> code/session -> token is the lock order.
  Proof consumption commits before grant transactions; replay revocation commits
  before errors are raised. HTTP mounting and resource authorization are separate."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.oauth.client :as client]
            [pds.oauth.client-auth :as client-auth]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.http :as http]
            [pds.oauth.par :as par]
            [pds.oauth.pkce :as pkce]
            [pds.oauth.proof-store :as proofs])
  (:import [java.time Instant]))

(def access-lifetime 300)
(def public-lifetime (* 14 86400))
(def confidential-lifetime (* 180 86400))
(defn- now [] (Instant/ofEpochSecond (dpop/now)))
(defn- invalid! [] (http/fail! "invalid_grant" "Invalid authorization grant"))
(defn- live? [timestamp] (and timestamp (.isAfter (.toInstant ^java.sql.Timestamp timestamp) (now))))
(defn- snapshot [row]
  (let [value (json/read-str (:snapshot row))]
    {:client-id (get value "client-id") :parameters (get value "parameters") :dpop-jkt (get value "dpop-jkt")
     :client-binding (into {} (map (fn [[k v]] [(keyword k) v]) (get value "client-binding")))}))
(defn- code? [value] (and (string? value) (boolean (re-matches #"[A-Za-z0-9_-]{43}" value))))
(defn- refresh? [value] (and (string? value) (boolean (re-matches #"rt_[A-Za-z0-9_-]{43}" value))))
(defn- lock-account! [conn did] (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" did)))
(defn- account-valid? [account row] (and (= "active" (:status account)) (= (:oauth_epoch account) (:account_epoch row))))
(defn- revoke! [conn session-id reason]
  (db/execute! conn "UPDATE oauth_sessions SET revoked_at = ?, revoke_reason = ? WHERE session_id = ? AND revoked_at IS NULL"
               (now) reason session-id)
  {:error "invalid_grant"})
(defn- code-row [conn code lock?]
  (when (code? code)
    (first (db/query conn (str "SELECT *, snapshot::text FROM oauth_codes WHERE code_hash = ?" (when lock? " FOR UPDATE"))
                     (crypto/digest-token code)))))
(defn- refresh-row [conn token]
  (when (refresh? token)
    (first (db/query conn "SELECT s.*, s.snapshot::text, t.token_hash FROM oauth_tokens t
                           JOIN oauth_sessions s USING (session_id) WHERE t.token_hash = ? AND t.kind = 'refresh'"
                     (crypto/digest-token token)))))
(defn- scope! [value]
  (try (client/scopes! value) (catch Exception _ (http/fail! "invalid_scope" "Invalid scope"))))
(defn- requested-scope! [request params]
  (let [original (get-in request [:parameters "scope"]) scope (get params "scope" original)
        granted (scope! scope)]
    (when-not (and (set/subset? granted (scope! original))
                   (or (not (granted "transition:chat.bsky")) (granted "transition:generic")))
      (http/fail! "invalid_scope" "Scope exceeds the original grant"))
    scope))
(defn- mint! [conn row scope refresh-enabled?]
  (let [time (now) deadline (.toInstant ^java.sql.Timestamp (:expires_at row))
        expires (min (+ (.getEpochSecond time) access-lifetime) (.getEpochSecond deadline))
        access (str "at_" (crypto/token)) refresh (when refresh-enabled? (str "rt_" (crypto/token)))
        original (get-in (snapshot row) [:parameters "scope"])]
    (when-not (< (dpop/now) expires) (invalid!))
    (db/execute! conn "INSERT INTO oauth_tokens(token_hash, session_id, kind, scope, created_at, expires_at) VALUES (?, ?, 'access', ?, ?, ?)"
                 (crypto/digest-token access) (:session_id row) scope time (Instant/ofEpochSecond expires))
    (when refresh
      (db/execute! conn "INSERT INTO oauth_tokens(token_hash, session_id, kind, scope, created_at, expires_at) VALUES (?, ?, 'refresh', ?, ?, ?)"
                   (crypto/digest-token refresh) (:session_id row) original time deadline))
    (cond-> {:access_token access :token_type "DPoP" :expires_in (- expires (.getEpochSecond time)) :sub (:did row) :scope scope}
      refresh (assoc :refresh_token refresh))))

(defn- exchange! [conn candidate resolved params]
  (let [account (lock-account! conn (:did candidate)) row (code-row conn (get params "code") true)
        request (when row (snapshot row))]
    (when-not row (invalid!))
    (when-not (= (get params "redirect_uri") (get-in request [:parameters "redirect_uri"])) (invalid!))
    (pkce/verify! (get-in request [:parameters "code_challenge"]) (get params "code_verifier"))
    (if (:used_at row)
      (do (db/execute! conn "UPDATE oauth_sessions SET revoked_at = ?, revoke_reason = 'code_replay' WHERE code_hash = ? AND revoked_at IS NULL"
                      (now) (:code_hash row))
          {:error "invalid_grant"})
      (do
        (when-not (and (live? (:expires_at row)) (account-valid? account row)) (invalid!))
        (par/parameters! resolved (:parameters request))
        (let [time (now) id (crypto/token)
              refresh? (boolean (some #{"refresh_token"} (get-in resolved [:metadata "grant_types"])))
              lifetime (if refresh? (if (= "none" (get-in request [:client-binding :method])) public-lifetime confidential-lifetime) access-lifetime)]
          (db/execute! conn "INSERT INTO oauth_sessions(session_id, code_hash, did, account_epoch, client_id, snapshot, created_at, expires_at)
                             VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)"
                       id (:code_hash row) (:did row) (:account_epoch row) (:client-id request) (:snapshot row) time (.plusSeconds time lifetime))
          (db/execute! conn "UPDATE oauth_codes SET used_at = ? WHERE code_hash = ?" time (:code_hash row))
          (mint! conn (first (db/query conn "SELECT *, snapshot::text FROM oauth_sessions WHERE session_id = ?" id))
                 (get-in request [:parameters "scope"]) refresh?))))))

(defn- refresh! [conn candidate resolved params]
  (let [account (lock-account! conn (:did candidate))
        row (first (db/query conn "SELECT *, snapshot::text FROM oauth_sessions WHERE session_id = ? FOR UPDATE" (:session_id candidate)))
        token (first (db/query conn "SELECT * FROM oauth_tokens WHERE token_hash = ? AND kind = 'refresh' FOR UPDATE" (:token_hash candidate)))]
    (when-not (and row token (nil? (:revoked_at row)) (live? (:expires_at row))) (invalid!))
    (cond
      (not (account-valid? account row)) (revoke! conn (:session_id row) "account_changed")
      (:used_at token) (revoke! conn (:session_id row) "refresh_replay")
      (not (live? (:expires_at token))) (invalid!)
      (not (some #{"refresh_token"} (get-in resolved [:metadata "grant_types"]))) (revoke! conn (:session_id row) "refresh_grant_removed")
      :else
      (let [request (snapshot row) scope (requested-scope! request params)]
        (par/parameters! resolved (:parameters request))
        (db/execute! conn "UPDATE oauth_tokens SET used_at = ? WHERE token_hash = ?" (now) (:token_hash token))
        (mint! conn row scope true)))))

(defn- invalidate-binding! [ds candidate]
  (db/transact! ds
    (fn [conn]
      (lock-account! conn (:did candidate))
      (if-let [id (:session_id candidate)]
        (revoke! conn id "client_key_removed")
        (do (db/execute! conn "UPDATE oauth_codes SET used_at = COALESCE(used_at, ?) WHERE code_hash = ?" (now) (:code_hash candidate))
            (db/execute! conn "UPDATE oauth_sessions SET revoked_at = ?, revoke_reason = 'client_key_removed' WHERE code_hash = ? AND revoked_at IS NULL"
                         (now) (:code_hash candidate)))))))

(defn issue!
  "Exchange an authorization code or rotate a refresh token. Client metadata is
  refreshed outside database transactions. Errors requiring revocation are raised
  only after committing it; mint failure rolls back code/refresh consumption."
  [ds resolver settings params proof]
  (let [grant (get params "grant_type")
        _ (when-not (#{"authorization_code" "refresh_token"} grant) (http/fail! "unsupported_grant_type" "Unsupported grant type"))
        candidate (with-open [conn (db/connection ds)]
                    (case grant "authorization_code" (code-row conn (get params "code") false)
                                "refresh_token" (refresh-row conn (get params "refresh_token"))))
        request (when candidate (snapshot candidate))
        resolved (client/resolve! resolver (get params "client_id"))
        matches? (= (:client-id request) (:client-id resolved))
        context {:method "POST" :url (str (:public-url settings) "/oauth/token") :jkt (when matches? (:dpop-jkt request))}]
    (when (and matches? (not (client-auth/binding-current? resolved (:client-binding request))))
      ;; A removed published key is authoritative even though that key can no
      ;; longer authenticate an assertion. Require the bound DPoP key first.
      (proofs/accept! ds settings proof context)
      (invalidate-binding! ds candidate)
      (client-auth/invalid!))
    (client-auth/accept-request! ds resolved settings params proof context (when matches? (:client-binding request)))
    (when-not (and candidate matches?) (invalid!))
    (let [result (db/transact! ds #(case grant "authorization_code" (exchange! % candidate resolved params)
                                              "refresh_token" (refresh! % candidate resolved params)))]
      (if (:error result) (invalid!) result))))

(defn access-grant!
  "Load an access grant inside the caller's transaction. This is NOT request
  authentication: the caller must verify/consume bound DPoP (including ath),
  revalidate metadata as required, and enforce scope before touching resources."
  [conn token]
  (when (.getAutoCommit ^java.sql.Connection conn) (throw (ex-info "Access grants require a transaction" {})))
  (when-not (and (string? token) (re-matches #"at_[A-Za-z0-9_-]{43}" token)) (invalid!))
  (let [hash (crypto/digest-token token)
        candidate (first (db/query conn "SELECT s.did, s.session_id FROM oauth_sessions s JOIN oauth_tokens t USING (session_id) WHERE t.token_hash = ? AND t.kind = 'access'" hash))
        account (when candidate (lock-account! conn (:did candidate)))
        row (when candidate (first (db/query conn "SELECT *, snapshot::text FROM oauth_sessions WHERE session_id = ? FOR UPDATE" (:session_id candidate))))
        token-row (first (db/query conn "SELECT * FROM oauth_tokens WHERE token_hash = ? AND kind = 'access'" hash))]
    (when-not (and row token-row (nil? (:revoked_at row)) (account-valid? account row)
                   (live? (:expires_at row)) (live? (:expires_at token-row))) (invalid!))
    {:did (:did row) :session-id (:session_id row) :client-id (:client_id row)
     :scope (:scope token-row) :dpop-jkt (:dpop-jkt (snapshot row)) :client-binding (:client-binding (snapshot row))}))

(defn handler [ds settings resolver]
  (http/wrap settings
    (fn [request]
      (when (or (seq (:query-string request)) (get-in request [:headers "authorization"]))
        (http/fail! "invalid_request" "Use form client authentication without query parameters"))
      (http/response 200 (issue! ds resolver settings (http/form! request) (get-in request [:headers "dpop"]))))))
