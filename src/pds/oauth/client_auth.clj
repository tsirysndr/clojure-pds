(ns pds.oauth.client-auth
  "OAuth client authentication against freshly resolved metadata. DPoP is separate."
  (:require [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.jose :as jose]
            [pds.oauth.proof-store :as proof-store]
            [pds.protocol.codec :as codec])
  (:import [java.time Instant]))

(def assertion-type "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
(defn invalid! [] (throw (ex-info "Client authentication failed" {:oauth-error "invalid_client"})))

(defn binding-current?
  "Whether refreshed metadata still advertises the exact authentication method
  and key used at session creation. Session owners must revoke on key removal."
  [{:keys [client-id metadata keys]} binding]
  (boolean
    (and (= client-id (:client-id binding))
         (= (get metadata "token_endpoint_auth_method") (:method binding))
         (case (:method binding)
           "none" (= binding {:client-id client-id :method "none"})
           "private_key_jwt" (some #(= (select-keys binding [:kid :alg :jkt]) (select-keys % [:kid :alg :jkt])) keys)
           false))))

(defn- audience? [value issuer]
  (and (string? issuer) (seq issuer)
       (or (= issuer value)
           (and (vector? value) (<= 1 (count value) 8)
                (every? #(and (string? %) (<= 1 (count %) 8192)) value)
                (some #{issuer} value)))))

(defn verify!
  "Verify public-client parameters or an ES256 private_key_jwt assertion. Client
  must come from fresh client/resolve! outside any transaction. An optional stored
  binding pins client ID, method, kid, algorithm and thumbprint across a session.
  The returned candidate must be consumed before accepting authentication."
  ([client settings params] (verify! client settings params nil))
  ([{:keys [client-id metadata keys] :as client} settings params expected-binding]
   (try
     (when-not (and (string? client-id) (= client-id (get params "client_id"))
                    (not (contains? params "client_secret"))) (invalid!))
     (let [method (get metadata "token_endpoint_auth_method")
           candidate
           (case method
             "none"
             (do (when (or (contains? params "client_assertion") (contains? params "client_assertion_type")) (invalid!))
                 {:binding {:client-id client-id :method "none"}})
             "private_key_jwt"
             (do
               (when-not (= assertion-type (get params "client_assertion_type")) (invalid!))
               (let [{:keys [header claims] :as parsed} (jose/parse! (get params "client_assertion"))
                     kid (get header "kid") algorithm (get header "alg")
                     matching (filter #(and (= kid (:kid %)) (= algorithm (:alg %))) keys)
                     issued (get claims "iat") expires (get claims "exp") not-before (get claims "nbf")
                     id (get claims "jti") timestamp (dpop/now)]
                 (when-not (and (string? kid) (<= 1 (count kid) 256) (= 1 (count matching))
                                (= "JWT" (get header "typ" "JWT"))
                                (= algorithm (get metadata "token_endpoint_auth_signing_alg" "ES256"))
                                (= client-id (get claims "iss")) (= client-id (get claims "sub"))
                                (audience? (get claims "aud") (:public-url settings))
                                (integer? issued) (<= 0 issued Long/MAX_VALUE) (<= (- timestamp 300) issued (+ timestamp 30))
                                (integer? expires) (< issued expires) (<= expires (+ issued 300)) (< timestamp expires)
                                (or (not (contains? claims "nbf"))
                                    (and (integer? not-before) (<= 0 not-before timestamp)))
                                (string? id) (<= 1 (alength (codec/utf8 id)) 256)) (invalid!))
                 (jose/verify! (first matching) parsed)
                 {:binding (merge {:client-id client-id :method method} (select-keys (first matching) [:kid :alg :jkt]))
                  :jti-hash (crypto/digest-token id) :expires-at expires}))
             (invalid!))]
       (when (and expected-binding (not= expected-binding (:binding candidate))) (invalid!))
       (when-not (binding-current? client (:binding candidate)) (invalid!))
       candidate)
     (catch Exception _ (invalid!)))))

(defn consume!
  "Consume a verified assertion once in an existing transaction. Replay identity
  is client ID+jti across all its keys, audiences and endpoint requests."
  [conn {:keys [binding jti-hash expires-at]}]
  (when (.getAutoCommit ^java.sql.Connection conn)
    (throw (ex-info "Client assertion consumption requires a transaction" {})))
  (when (= "private_key_jwt" (:method binding))
    (let [timestamp (dpop/now) now (Instant/ofEpochSecond timestamp)]
      (when-not (< timestamp expires-at) (invalid!))
      (db/execute! conn "DELETE FROM oauth_client_assertion_uses WHERE (client_id_hash, jti_hash) IN
                         (SELECT client_id_hash, jti_hash FROM oauth_client_assertion_uses WHERE expires_at <= ?
                          ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)" now)
      (when (zero? (db/execute! conn "INSERT INTO oauth_client_assertion_uses(client_id_hash, jti_hash, expires_at) VALUES (?, ?, ?)
                                    ON CONFLICT (client_id_hash, jti_hash) DO UPDATE SET expires_at = EXCLUDED.expires_at
                                    WHERE oauth_client_assertion_uses.expires_at <= ?"
                               (crypto/digest-token (:client-id binding)) jti-hash (Instant/ofEpochSecond expires-at) now))
        (invalid!))))
  nil)

(defn accept!
  "Commit authentication before business logic so failed grants cannot revive an
  assertion. Fresh metadata resolution must precede this call. No cache fallback."
  ([ds client settings params] (accept! ds client settings params nil))
  ([ds client settings params expected-binding]
   (let [candidate (verify! client settings params expected-binding)]
     (db/transact! ds #(consume! % candidate))
     (:binding candidate))))

(defn accept-request!
  "Authenticate both proofs for PAR/token requests. Verify the server nonce before
  spending the client assertion, then atomically consume both proofs before grant
  logic. Fresh client resolution and trusted DPoP target construction are callers'
  responsibilities; no network operation runs inside the transaction."
  [ds client settings params dpop-token context expected-binding]
  (let [proof (dpop/verify! settings dpop-token context)
        candidate (verify! client settings params expected-binding)]
    (db/transact! ds (fn [conn]
                      (proof-store/consume! conn proof)
                      (consume! conn candidate)))
    {:client-binding (:binding candidate) :dpop-jkt (:jkt proof)}))
