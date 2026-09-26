(ns pds.auth
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.codec :as codec])
  (:import [java.security MessageDigest]
           [java.time Instant]
           [java.util UUID]))

(defn settings [env]
  (let [key (try (crypto/unb64 (get env "PDS_MASTER_KEY" "")) (catch Exception _ nil))]
    (when-not (and key (= 32 (alength ^bytes key)))
      (throw (ex-info "PDS_MASTER_KEY must be a base64url-encoded 32-byte key" {})))
    {:master-key key
     :jwt-key (crypto/hmac key (codec/utf8 "clojure-pds/session-signing/v1"))}))
(defn now [] (.getEpochSecond (Instant/now)))
(defn invalid-token! [] (errors/raise! 401 "InvalidToken" "Invalid or expired session token"))
(defn jwt [settings type claims]
  (let [head (crypto/b64 (codec/utf8 (json/write-str {"alg" "HS256" "typ" type})))
        payload (crypto/b64 (codec/utf8 (json/write-str claims)))
        unsigned (str head "." payload)]
    (str unsigned "." (crypto/b64 (crypto/hmac (:jwt-key settings) (codec/utf8 unsigned))))))
(defn verify-jwt [settings type token]
  (try
    (when-not (and (string? token) (<= 1 (count token) 8192)) (invalid-token!))
    (let [[head payload signature :as parts] (str/split token #"\." -1)]
      (when-not (and (= 3 (count parts))
                     (MessageDigest/isEqual (crypto/unb64 signature)
                                            (crypto/hmac (:jwt-key settings) (codec/utf8 (str head "." payload)))))
        (invalid-token!))
      (let [header (json/read-str (codec/text (crypto/unb64 head)))
            claims (json/read-str (codec/text (crypto/unb64 payload)))]
        (when-not (and (= {"alg" "HS256" "typ" type} header)
                       (= (:service-did settings) (get claims "iss") (get claims "aud"))
                       (integer? (get claims "exp")) (< (now) (get claims "exp"))
                       (= (if (= type "at+jwt") "com.atproto.access" "com.atproto.refresh") (get claims "scope"))
                       (string? (get claims "sub")) (string? (get claims "sid")) (string? (get claims "jti")))
          (invalid-token!))
        claims))
    (catch Exception _ (invalid-token!))))

(defn issue! [conn settings did session-id]
  (let [id (or session-id (UUID/randomUUID))
        expires (+ (now) (* 90 24 3600))
        _ (when-not session-id
            (db/execute! conn "INSERT INTO sessions(id, did, expires_at) VALUES (?, ?, ?)"
                         id did (Instant/ofEpochSecond expires)))
        base {"iss" (:service-did settings) "aud" (:service-did settings) "sub" did "sid" (str id) "iat" (now)}
        access (jwt settings "at+jwt" (assoc base "scope" "com.atproto.access" "exp" (+ (now) 900) "jti" (crypto/token)))
        refresh (jwt settings "refresh+jwt" (assoc base "scope" "com.atproto.refresh" "exp" expires "jti" (crypto/token)))]
    (db/execute! conn "INSERT INTO refresh_tokens(token_hash, session_id, expires_at) VALUES (?, ?, ?)"
                 (crypto/digest-token refresh) id (Instant/ofEpochSecond expires))
    {:accessJwt access :refreshJwt refresh}))

(defn bearer [request]
  (let [value (get-in request [:headers "authorization"])]
    (if-let [[_ token] (and value (re-matches #"(?i)Bearer ([A-Za-z0-9_.-]+)" value))]
      token (errors/raise! 401 "AuthenticationRequired" "A Bearer token is required"))))

(defn authenticate! [conn settings request]
  (let [claims (verify-jwt settings "at+jwt" (bearer request))
        session (first (db/query conn "SELECT a.* FROM sessions s JOIN accounts a ON a.did = s.did
                                        WHERE s.id = ? AND s.did = ? AND NOT s.revoked
                                          AND s.expires_at > now() AND a.status = 'active'"
                                 (UUID/fromString (get claims "sid")) (get claims "sub")))]
    (when-not session (invalid-token!))
    (assoc session :session-id (UUID/fromString (get claims "sid")))))

(defn refresh! [ds settings request]
  (let [token (bearer request) claims (verify-jwt settings "refresh+jwt" token)
        result
        (db/transact!
         ds
         (fn [conn]
           (let [session (first (db/query conn "SELECT s.*, a.handle, a.status FROM sessions s JOIN accounts a ON s.did = a.did
                                                WHERE s.id = ? AND s.did = ? AND s.expires_at > now() FOR UPDATE OF s"
                                         (UUID/fromString (get claims "sid")) (get claims "sub")))
                 refresh (first (db/query conn "SELECT * FROM refresh_tokens WHERE token_hash = ? AND session_id = ? AND expires_at > now()"
                                         (crypto/digest-token token) (UUID/fromString (get claims "sid"))))]
             (cond
               (or (nil? session) (:revoked session) (not= "active" (:status session)) (nil? refresh)) nil
               (:used refresh) (do (db/execute! conn "UPDATE sessions SET revoked = true WHERE id = ?" (:id session)) nil)
               :else (do
                       (db/execute! conn "UPDATE refresh_tokens SET used = true WHERE token_hash = ?" (crypto/digest-token token))
                       (merge {:did (:did session) :handle (:handle session) :active true}
                              (issue! conn settings (:did session) (:id session))))))))]
    ;; Throw outside the transaction: revocation on replay must commit.
    (or result (invalid-token!))))

(defn delete-session! [ds settings request]
  (let [claims (verify-jwt settings "refresh+jwt" (bearer request))]
    (db/transact! ds #(db/execute! % "UPDATE sessions SET revoked = true WHERE id = ? AND did = ?"
                                  (UUID/fromString (get claims "sid")) (get claims "sub")))))
