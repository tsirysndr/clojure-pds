(ns pds.oauth.pkce
  (:require [pds.crypto :as crypto]
            [pds.protocol.codec :as codec])
  (:import [java.security MessageDigest]))

(defn challenge? [value]
  (boolean (and (string? value) (re-matches #"[A-Za-z0-9_-]{43}" value)
                (try (= value (crypto/b64 (crypto/unb64 value))) (catch Exception _ false)))))

(defn challenge! [value method]
  (when-not (and (= "S256" method) (challenge? value))
    (throw (ex-info "A canonical S256 code challenge is required" {:oauth-error "invalid_request"})))
  value)

(defn verify! [challenge verifier]
  (when-not (and (challenge? challenge) (string? verifier) (re-matches #"[A-Za-z0-9._~-]{43,128}" verifier)
                 (MessageDigest/isEqual (codec/utf8 challenge) (codec/utf8 (crypto/digest-token verifier))))
    (throw (ex-info "PKCE verification failed" {:oauth-error "invalid_grant"})))
  true)
