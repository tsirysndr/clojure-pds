(ns pds.service-auth
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.identity :as identity]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax]
            [pds.request :as request])
  (:import [java.net URI]
           [java.math BigInteger]
           [java.time Instant]
           [java.util Arrays]
           [org.bouncycastle.util BigIntegers]))

;; Match the reference PDS's case-insensitive policy at the pinned revision in
;; docs/COMPATIBILITY.md. This policy is shared with proxy authorization.
(def protected-methods
  (set (map str/lower-case
            ["com.atproto.admin.sendEmail" "com.atproto.identity.requestPlcOperationSignature"
             "com.atproto.identity.signPlcOperation" "com.atproto.identity.updateHandle"
             "com.atproto.server.activateAccount" "com.atproto.server.confirmEmail"
             "com.atproto.server.createAppPassword" "com.atproto.server.deactivateAccount"
             "com.atproto.server.getAccountInviteCodes" "com.atproto.server.getSession"
             "com.atproto.server.listAppPasswords" "com.atproto.server.requestAccountDelete"
             "com.atproto.server.requestEmailConfirmation" "com.atproto.server.requestEmailUpdate"
             "com.atproto.server.revokeAppPassword" "com.atproto.server.updateEmail"])))
(def privileged-methods
  (set (map str/lower-case
            ["com.atproto.server.createAccount" "chat.bsky.actor.deleteAccount" "chat.bsky.actor.exportAccountData"
             "chat.bsky.convo.deleteMessageForSelf" "chat.bsky.convo.getConvo" "chat.bsky.convo.getConvoForMembers"
             "chat.bsky.convo.getLog" "chat.bsky.convo.getMessages" "chat.bsky.convo.leaveConvo"
             "chat.bsky.convo.listConvos" "chat.bsky.convo.muteConvo" "chat.bsky.convo.sendMessage"
             "chat.bsky.convo.sendMessageBatch" "chat.bsky.convo.unmuteConvo" "chat.bsky.convo.updateRead"])))

(defn audience? [value]
  (boolean
    (and (string? value) (<= 1 (count value) 2048)
         (let [[did fragment :as parts] (str/split value #"#" -1)]
           (and (<= (count parts) 2) (syntax/did? did)
                (or (= 1 (count parts))
                    (re-matches #"(?:[A-Za-z0-9._~!$&'()*+,;=:@/?-]|%[0-9A-Fa-f]{2})+" fragment))
                (or (re-matches #"did:plc:[a-z2-7]{24}" did)
                    (and (str/starts-with? did "did:web:")
                         (not (str/includes? (subs did 8) ":"))
                         (try
                           (let [uri (URI/create (str "https://" (str/replace (subs did 8) "%3A" ":")))]
                             (and (seq (.getHost uri)) (nil? (.getUserInfo uri))
                                  (or (= -1 (.getPort uri))
                                      (and (= "localhost" (.getHost uri)) (<= 0 (.getPort uri) 65535)))))
                           (catch Exception _ false)))))))))

(defn parameters! [params now]
  (let [audience (get params "aud") method (get params "lxm") raw-exp (get params "exp")]
    (when-not (audience? audience) (errors/invalid! "aud must be an AT Protocol DID or DID service reference"))
    (when (and (contains? params "lxm") (not (syntax/nsid? method))) (errors/invalid! "lxm must be an NSID"))
    (let [expires (if (contains? params "exp")
                    (or (when (and (string? raw-exp) (re-matches #"-?[0-9]{1,19}" raw-exp)) (parse-long raw-exp))
                        (errors/raise! 400 "BadExpiration" "exp must be a Unix timestamp in seconds"))
                    (+ now 60))]
      (when-not (< now expires (+ now (if method 3600 60) 1))
        (errors/raise! 400 "BadExpiration" "Expiration must be in the future, at most one hour for a method or one minute without one"))
      {:audience audience :method method :expires expires})))

(defn authorize! [account method]
  (let [method (some-> method str/lower-case)]
    (when (and (= "taken_down" (:status account)) (not= "com.atproto.server.createaccount" method))
      (errors/raise! 400 "InvalidToken" "Taken-down accounts may only authorize account migration"))
    (when (protected-methods method) (errors/invalid! "This method cannot use service authentication"))
    (when (and (privileged-methods method)
               (not (#{"com.atproto.access" "com.atproto.appPassPrivileged"} (:access-scope account))))
      (errors/invalid! "A primary or privileged app-password session is required for this method"))))

(defn sign
  "Sign a short-lived service JWT using the repository key, not the session key.
  Callers authorize the audience, method and lifetime before signing."
  [key issuer audience method issued expires]
  (let [header {"typ" "JWT" "alg" (:algorithm key) "kid" "#atproto"}
        claims (cond-> {"iss" issuer "aud" audience "iat" issued "exp" expires "jti" (crypto/token)}
                 method (assoc "lxm" method))
        unsigned (str (crypto/b64 (codec/utf8 (json/write-str header))) "."
                      (crypto/b64 (codec/utf8 (json/write-str claims))))]
    (str unsigned "." (crypto/b64 (crypto/sign (:algorithm key) (:private key) (codec/utf8 unsigned))))))

(defn issue! [conn settings account params]
  (let [now (auth/now) {:keys [audience method expires]} (parameters! params now)]
    (authorize! account method)
    (let [repo (first (db/query conn "SELECT signing_key FROM repositories WHERE did = ?" (:did account)))]
      (when-not repo (errors/raise! 400 "RepoNotFound" "Repository was not found"))
      {:token (sign {:algorithm "ES256" :private (crypto/unseal (:master-key settings) (:did account) (:signing_key repo))}
                    (:did account) audience method now expires)})))

(defn- invalid! [error message] (errors/raise! 401 error message))
(defn- decode-segment! [value]
  (when-not (and (string? value) (re-matches #"[A-Za-z0-9_-]+" value))
    (invalid! "BadJwt" "Invalid service token encoding"))
  (let [bytes (crypto/unb64 value)]
    (when-not (= value (crypto/b64 bytes)) (invalid! "BadJwt" "Noncanonical service token encoding"))
    bytes))

(defn- time-valid! [claims]
  (let [issued (get claims "iat") expires (get claims "exp") now (auth/now)]
    (when-not (and (integer? issued) (integer? expires)
                   (<= 0 issued Long/MAX_VALUE) (<= 0 expires Long/MAX_VALUE)
                   (< issued expires) (<= (- expires issued) 3600)
                   (<= issued (+ now 30)))
      (invalid! "BadJwt" "Invalid service token lifetime"))
    (when (<= expires now) (invalid! "JwtExpired" "Service token has expired"))
    (when (contains? claims "nbf")
      (when-not (and (integer? (get claims "nbf")) (<= 0 (get claims "nbf") now))
        (invalid! "BadJwt" "Service token is not yet valid")))))

(defn- parse! [token audiences method]
  (try
    (when-not (and (string? token) (<= 1 (count token) 8192)) (invalid! "BadJwt" "Invalid service token size"))
    (let [[h p s :as parts] (str/split token #"\." -1)]
      (when-not (= 3 (count parts)) (invalid! "BadJwt" "Invalid service token format"))
      (let [header (request/json-value (decode-segment! h)) claims (request/json-value (decode-segment! p))
            signature (decode-segment! s)]
        (when-not (and (map? header) (= "JWT" (get header "typ"))
                       (#{"ES256" "ES256K"} (get header "alg"))
                       (= "#atproto" (get header "kid" "#atproto"))
                       (not (contains? header "crit")) (not (contains? header "b64")))
          (invalid! "BadJwt" "Unsupported service token header"))
        (when-not (and (map? claims) (identity/supported-did? (get claims "iss"))
                       (string? (get claims "jti")) (<= 1 (alength (codec/utf8 (get claims "jti"))) 256)
                       (= 64 (alength signature)))
          (invalid! "BadJwt" "Invalid service token claims or signature"))
        (when-not (contains? audiences (get claims "aud")) (invalid! "BadJwtAudience" "Service token audience does not match this service"))
        (when-not (and (syntax/nsid? method) (= method (get claims "lxm")))
          (invalid! "BadJwtLexiconMethod" "Service token method does not match this endpoint"))
        (time-valid! claims)
        {:header header :claims claims :signature signature :message (codec/utf8 (str h "." p))}))
    (catch Exception e
      (if (= 401 (:status (ex-data e))) (throw e)
          (invalid! "BadJwt" "Invalid service token")))))

(defn- service-signature [algorithm signature]
  ;; The upstream JWT verifier permits high-S ECDSA signatures. Normalize only
  ;; here; repository/PLC verification continues to require canonical low-S.
  ;; Replay identity uses issuer+jti, so a malleated signature gets no new use.
  (let [n (.getN (crypto/domain algorithm))
        s (BigInteger. 1 (Arrays/copyOfRange ^bytes signature 32 64))]
    (when (and (pos? (.signum s)) (neg? (.compareTo s n)))
      (byte-array (concat (take 32 signature) (BigIntegers/asUnsignedByteArray 32 (.min s (.subtract n s))))))))

(defn verify!
  "Verify a remote service request without holding a database transaction.
  Returns proof for consume!; callers must consume in the protected mutation's
  transaction. Only the configured PDS audience (bare legacy or #atproto_pds) and
  #atproto signing key are accepted. Local session authentication is unchanged."
  [resolver settings request method]
  (let [audiences #{(:service-did settings) (str (:service-did settings) "#atproto_pds")}
        {:keys [header claims signature message]} (parse! (auth/bearer request) audiences method)
        document (identity/bounded-call! resolver #(identity/resolve-did! resolver (get claims "iss")))
        key (identity/signing-key document)]
    (when-not (and (= (get claims "iss") (get document "id")) key (= (:algorithm key) (get header "alg"))
                   (when-let [signature (service-signature (:algorithm key) signature)]
                     (crypto/verify (:algorithm key) (:public key) message signature)))
      (invalid! "BadJwtSignature" "Service token signature does not match its issuer"))
    ;; DNS/HTTPS work can outlive a short token.
    (time-valid! claims)
    {:did (get claims "iss") :claims claims :document document}))

(defn consume!
  "Record a verified issuer+nonce once, atomically with the protected mutation.
  A rolled-back mutation does not spend its token; a committed use survives
  process restarts and is shared by every PDS instance using this database."
  [conn {:keys [claims]}]
  (when (.getAutoCommit ^java.sql.Connection conn)
    (throw (ex-info "Service token consumption requires a transaction" {})))
  (time-valid! claims)
  (let [now (Instant/ofEpochSecond (auth/now))]
    (db/execute! conn "DELETE FROM service_token_uses WHERE (issuer, nonce_hash) IN
                       (SELECT issuer, nonce_hash FROM service_token_uses WHERE expires_at <= ?
                        ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)" now)
    (when (zero? (db/execute! conn "INSERT INTO service_token_uses(issuer, nonce_hash, expires_at) VALUES (?, ?, ?)
                                  ON CONFLICT (issuer, nonce_hash) DO UPDATE SET expires_at = EXCLUDED.expires_at
                                  WHERE service_token_uses.expires_at <= ?"
                             (get claims "iss") (crypto/digest-token (get claims "jti"))
                             (Instant/ofEpochSecond (get claims "exp")) now))
      (invalid! "JwtReplay" "Service token has already been used")))
  nil)
