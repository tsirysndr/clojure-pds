(ns pds.oauth.par
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.oauth.client :as client]
            [pds.oauth.client-auth :as client-auth]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.http :as http]
            [pds.oauth.grants :as grants]
            [pds.oauth.pkce :as pkce]
            [pds.oauth.scope :as scope]
            [pds.protocol.codec :as codec])
  (:import [java.time Instant]))

(def request-uri-prefix "urn:ietf:params:oauth:request_uri:")
(def request-lifetime 90)
(def supported-scopes scope/transitional)

(defn parameters! [resolved params]
  (when-not (= "code" (get params "response_type")) (http/fail! "unsupported_response_type" "Only authorization code responses are supported"))
  (when (some #(contains? params %) ["request" "request_uri" "code_verifier"])
    (http/fail! "invalid_request" "Nested requests and verifiers are not accepted at PAR"))
  (when-not (= "query" (get params "response_mode" "query")) (http/fail! "invalid_request" "Unsupported response mode"))
  (when (contains? params "prompt")
    (when-not (#{"login" "consent" "select_account" "create"} (get params "prompt")) (http/fail! "invalid_request" "Unsupported prompt")))
  (doseq [[key maximum required?] [["state" 1024 true] ["login_hint" 2048 false]]]
    (when (or required? (contains? params key))
      (let [value (get params key)]
        (when-not (and (string? value) (<= (if required? 1 0) (alength (codec/utf8 value)) maximum))
          (http/fail! "invalid_request" "Missing or oversized authorization parameter")))))
  (client/redirect! resolved (get params "redirect_uri"))
  (pkce/challenge! (get params "code_challenge") (get params "code_challenge_method"))
  (let [requested (try (client/scopes! (get params "scope")) (catch Exception _ (http/fail! "invalid_scope" "Invalid scope")))
        declared (client/scopes! (get-in resolved [:metadata "scope"]))]
    (when-not (and (set/subset? requested declared) (every? scope/supported? requested)
                   (or (not (requested "transition:chat.bsky")) (requested "transition:generic")))
      (http/fail! "invalid_scope" "Requested scope is not available")))
  (grants/validate-scope! (get params "scope"))
  ;; Persist only authorization parameters, never client assertions or arbitrary
  ;; extension fields. Future permissions support must extend validation first.
  (select-keys params ["response_type" "redirect_uri" "scope" "state" "code_challenge" "code_challenge_method"
                      "response_mode" "login_hint" "prompt"]))

(defn- store! [conn client-id params {:keys [client-binding dpop-jkt]} permission-sets]
  (let [timestamp (dpop/now) now (Instant/ofEpochSecond timestamp)
        request-uri (str request-uri-prefix (crypto/token))]
    (db/execute! conn "DELETE FROM oauth_pkce_uses WHERE challenge_hash IN
                       (SELECT challenge_hash FROM oauth_pkce_uses WHERE expires_at <= ? ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)" now)
    (db/execute! conn "DELETE FROM oauth_par_requests WHERE request_hash IN
                       (SELECT request_hash FROM oauth_par_requests WHERE expires_at <= ? ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)" now)
    (when (zero? (db/execute! conn "INSERT INTO oauth_pkce_uses(challenge_hash, expires_at) VALUES (?, ?)
                                  ON CONFLICT (challenge_hash) DO UPDATE SET expires_at = EXCLUDED.expires_at WHERE oauth_pkce_uses.expires_at <= ?"
                             (crypto/digest-token (get params "code_challenge")) (Instant/ofEpochSecond (+ timestamp 86400)) now))
      (http/fail! "invalid_request" "PKCE challenges must not be reused"))
    (db/execute! conn "INSERT INTO oauth_par_requests(request_hash, client_id, parameters, client_binding, dpop_jkt, created_at, expires_at, permission_sets)
                       VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb)"
                 (crypto/digest-token request-uri) client-id (json/write-str params) (json/write-str client-binding) dpop-jkt
                 now (Instant/ofEpochSecond (+ timestamp request-lifetime)) (json/write-str permission-sets))
    {:request_uri request-uri :expires_in request-lifetime}))

(defn push! [ds resolver settings params proof]
  (let [resolved (client/resolve! resolver (get params "client_id"))
        validated (parameters! resolved params)
        expected-jkt (when (contains? params "dpop_jkt") (get params "dpop_jkt"))]
    (when (and (contains? params "dpop_jkt") (not (pkce/challenge? expected-jkt)))
      (http/fail! "invalid_request" "Invalid DPoP thumbprint"))
    (let [authenticated (client-auth/accept-request! ds resolved settings params proof
                                                   {:method "POST" :url (str (:public-url settings) "/oauth/par") :jkt expected-jkt} nil)]
      ;; Authentication commits before grant logic; a rejected reuse needs fresh
      ;; proofs. Challenge reservation and request creation commit together.
      (let [snapshots (grants/resolve! ds settings (get validated "scope") nil)]
        (grants/permissions (get validated "scope") snapshots)
        (db/transact! ds #(store! % (:client-id resolved) validated authenticated snapshots))))))

(defn claim!
  "Take a request URI once for the matching client, inside the caller's browser
  interaction transaction. Save the returned snapshot before committing; rollback
  preserves the URI if interaction creation fails. No redirects or I/O here."
  [conn client-id request-uri]
  (when (.getAutoCommit ^java.sql.Connection conn) (throw (ex-info "PAR consumption requires a transaction" {})))
  (when-not (and (string? request-uri)
                 (re-matches #"urn:ietf:params:oauth:request_uri:[A-Za-z0-9_-]{43}" request-uri))
    (http/fail! "invalid_request_uri" "Invalid request URI"))
  (let [now (Instant/ofEpochSecond (dpop/now))
        row (first (db/query conn "UPDATE oauth_par_requests SET used_at = ?
                                  WHERE request_hash = ? AND client_id = ? AND expires_at > ? AND used_at IS NULL
                                  RETURNING client_id, parameters::text, client_binding::text, dpop_jkt, permission_sets::text"
                             now (crypto/digest-token request-uri) client-id now))]
    (when-not row (http/fail! "invalid_request_uri" "Invalid or expired request URI"))
    {:client-id (:client_id row) :parameters (json/read-str (:parameters row))
     :client-binding (json/read-str (:client_binding row) :key-fn keyword) :dpop-jkt (:dpop_jkt row)
     :permission-sets (json/read-str (:permission_sets row))}))

(defn handler [ds settings resolver]
  (http/wrap settings
    (fn [request]
      (when (or (seq (:query-string request)) (get-in request [:headers "authorization"]))
        (http/fail! "invalid_request" "Use form client authentication without query parameters"))
      (http/response 201 (push! ds resolver settings (http/form! request) (get-in request [:headers "dpop"]))))))
