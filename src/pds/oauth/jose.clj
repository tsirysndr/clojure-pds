(ns pds.oauth.jose
  "Bounded ES256 JOSE primitives. Repository/PLC signature rules stay separate."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.protocol.codec :as codec]
            [pds.request :as request])
  (:import [java.math BigInteger]
           [java.util Arrays]
           [org.bouncycastle.crypto.params ECPublicKeyParameters]
           [org.bouncycastle.util BigIntegers]))

(defn invalid! [] (throw (ex-info "Invalid OAuth signing material" {:jose true})))

(defn decode! [value]
  (when-not (and (string? value) (re-matches #"[A-Za-z0-9_-]+" value)) (invalid!))
  (let [bytes (crypto/unb64 value)]
    (when-not (= value (crypto/b64 bytes)) (invalid!))
    bytes))

(defn public-key!
  "Accept a public P-256 JWK and return its compressed key and RFC 7638 thumbprint.
  Optional JWK fields do not affect the thumbprint. Never follow key URLs."
  [jwk]
  (try
    (when-not (and (map? jwk) (= "EC" (get jwk "kty")) (= "P-256" (get jwk "crv"))
                   (not-any? #(contains? jwk %) ["d" "k" "p" "q" "dp" "dq" "qi" "oth"])
                   (= "ES256" (get jwk "alg" "ES256")) (= "sig" (get jwk "use" "sig"))
                   (or (not (contains? jwk "key_ops"))
                       (and (vector? (get jwk "key_ops")) (= ["verify"] (get jwk "key_ops")))))
      (invalid!))
    (let [x (decode! (get jwk "x")) y (decode! (get jwk "y")) params (crypto/domain "ES256")]
      (when-not (and (= 32 (alength x)) (= 32 (alength y))) (invalid!))
      (let [point (.decodePoint (.getCurve params) (byte-array (concat [4] x y)))
            _ (ECPublicKeyParameters. point params)
            canonical (json/write-str (into (sorted-map) (select-keys jwk ["crv" "kty" "x" "y"])))]
        {:public (.getEncoded point true) :jkt (crypto/digest-token canonical)}))
    (catch Exception _ (invalid!))))

(defn public-jwk [public]
  (let [params (crypto/domain "ES256") point (.normalize (.decodePoint (.getCurve params) public))]
    (ECPublicKeyParameters. point params)
    {"kty" "EC" "crv" "P-256"
     "x" (crypto/b64 (BigIntegers/asUnsignedByteArray 32 (.toBigInteger (.getAffineXCoord point))))
     "y" (crypto/b64 (BigIntegers/asUnsignedByteArray 32 (.toBigInteger (.getAffineYCoord point))))}))

(defn parse! [token]
  (try
    (when-not (and (string? token) (<= 1 (count token) 16384)) (invalid!))
    (let [[h p s :as parts] (str/split token #"\." -1)]
      (when-not (= 3 (count parts)) (invalid!))
      (let [header (request/json-value (decode! h)) claims (request/json-value (decode! p)) signature (decode! s)]
        (when-not (and (map? header) (map? claims) (= "ES256" (get header "alg"))
                       (not (contains? header "crit")) (true? (get header "b64" true))
                       (= 64 (alength signature))) (invalid!))
        {:header header :claims claims :signature signature :message (codec/utf8 (str h "." p))}))
    (catch Exception _ (invalid!))))

(defn verify! [key {:keys [signature message] :as parsed}]
  ;; JOSE permits both ECDSA S forms. Repository and PLC verification retains
  ;; its stricter low-S requirement; replay tracking uses key+jti, not bytes.
  (let [n (.getN (crypto/domain "ES256"))
        s (BigInteger. 1 (Arrays/copyOfRange ^bytes signature 32 64))]
    (when-not (and (pos? (.signum s)) (neg? (.compareTo s n))
                   (crypto/verify "ES256" (:public key) message
                                  (byte-array (concat (take 32 signature)
                                                      (BigIntegers/asUnsignedByteArray 32 (.min s (.subtract n s)))))))
      (invalid!)))
  parsed)
