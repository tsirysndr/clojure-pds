(ns pds.security.totp
  "RFC 4226/6238 primitives. The account policy uses SHA-1, six digits, 30 seconds."
  (:require [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.protocol.codec :as codec])
  (:import [java.net URLEncoder]
           [java.nio ByteBuffer]
           [java.security MessageDigest]
           [java.util Locale]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn code
  ([secret step] (code secret step "HmacSHA1" 6))
  ([^bytes secret step algorithm digits]
   (when-not (and (#{"HmacSHA1" "HmacSHA256" "HmacSHA512"} algorithm)
                  (#{6 8} digits) (integer? step) (<= 0 step Long/MAX_VALUE)
                  (<= 20 (alength secret) 64))
     (throw (ex-info "Invalid TOTP parameters" {})))
   (let [mac (doto (Mac/getInstance algorithm) (.init (SecretKeySpec. secret algorithm)))
         digest (.doFinal mac (.array (doto (ByteBuffer/allocate 8) (.putLong step))))
         offset (bit-and 15 (aget digest (dec (alength digest))))
         binary (bit-and 0x7fffffff (.getInt (ByteBuffer/wrap digest) offset))
         modulus (if (= digits 6) 1000000 100000000)
         value (str (mod binary modulus))]
     (str (apply str (repeat (- digits (count value)) \0)) value))))

(defn matching-step
  "Return the newest matching step in a ±30-second window, or nil. Account
  persistence must atomically reject previously accepted steps. Never accepts
  whitespace, non-ASCII digits or a missing leading zero."
  [secret seconds supplied]
  (when (and (integer? seconds) (<= 0 seconds Long/MAX_VALUE)
             (string? supplied) (re-matches #"[0-9]{6}" supplied))
    (let [current (quot seconds 30) candidate (codec/utf8 supplied)]
      (last (filter (fn [step] (MessageDigest/isEqual candidate (codec/utf8 (code secret step))))
                    (filter #(>= % 0) [(dec current) current (inc current)]))))))

(defn secret [] (crypto/random-bytes 20))
(defn encoded-secret [secret] (.toUpperCase ^String (codec/base32 secret) Locale/ROOT))
(defn provisioning-uri [secret issuer account]
  (when-not (every? #(and (string? %) (<= 1 (count %) 256) (not (re-find #"[:\p{Cntrl}]" %))) [issuer account])
    (throw (ex-info "Invalid authenticator issuer/account label" {})))
  (let [encode #(str/replace (URLEncoder/encode % "UTF-8") "+" "%20")]
    (str "otpauth://totp/" (encode issuer) ":" (encode account)
         "?secret=" (encoded-secret secret) "&issuer=" (encode issuer)
         "&algorithm=SHA1&digits=6&period=30")))
