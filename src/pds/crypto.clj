(ns pds.crypto
  (:require [clojure.string :as str]
            [pds.protocol.codec :as codec])
  (:import [java.math BigInteger]
           [java.security MessageDigest SecureRandom]
           [java.util Arrays Base64]
           [javax.crypto Cipher Mac]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]
           [org.bouncycastle.asn1.sec SECNamedCurves]
           [org.bouncycastle.crypto.digests SHA256Digest]
           [org.bouncycastle.crypto.generators Argon2BytesGenerator ECKeyPairGenerator]
           [org.bouncycastle.crypto.params Argon2Parameters Argon2Parameters$Builder ECDomainParameters ECKeyGenerationParameters ECPrivateKeyParameters ECPublicKeyParameters]
           [org.bouncycastle.crypto.signers ECDSASigner HMacDSAKCalculator]
           [org.bouncycastle.util BigIntegers]))

(def random (SecureRandom.))
(defn random-bytes [size] (let [out (byte-array size)] (.nextBytes random out) out))
(defn b64 [^bytes bytes] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))
(defn unb64 [^String value] (.decode (Base64/getUrlDecoder) value))
(defn token [] (b64 (random-bytes 32)))
(defn digest-token [value] (b64 (codec/sha256 (codec/utf8 value))))
(defn hmac [key data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. key "HmacSHA256")) (.doFinal mac data)))

(defn domain [algorithm]
  (let [curve (SECNamedCurves/getByName (case algorithm "ES256" "secp256r1" "ES256K" "secp256k1"
                                           (codec/fail! "Unsupported signing algorithm")))]
    (ECDomainParameters. (.getCurve curve) (.getG curve) (.getN curve) (.getH curve))))

(defn keypair
  ([] (keypair "ES256"))
  ([algorithm]
   (let [gen (ECKeyPairGenerator.)]
     (.init gen (ECKeyGenerationParameters. (domain algorithm) random))
     (let [pair (.generateKeyPair gen)]
       {:algorithm algorithm
        :private (BigIntegers/asUnsignedByteArray 32 (.getD ^ECPrivateKeyParameters (.getPrivate pair)))
        :public (.getEncoded (.getQ ^ECPublicKeyParameters (.getPublic pair)) true)}))))

(defn sign [algorithm private message]
  (let [params (domain algorithm)
        signer (ECDSASigner. (HMacDSAKCalculator. (SHA256Digest.)))]
    (.init signer true (ECPrivateKeyParameters. (BigInteger. 1 private) params))
    (let [[r s] (.generateSignature signer (codec/sha256 message))
          s (.min ^BigInteger s (.subtract (.getN params) s))]
      (byte-array (concat (BigIntegers/asUnsignedByteArray 32 r) (BigIntegers/asUnsignedByteArray 32 s))))))

(defn verify [algorithm public message signature]
  (try
    (and (= 64 (alength ^bytes signature))
         (let [params (domain algorithm)
               r (BigInteger. 1 (Arrays/copyOfRange ^bytes signature 0 32))
               s (BigInteger. 1 (Arrays/copyOfRange ^bytes signature 32 64))
               signer (ECDSASigner.)]
           (and (pos? (.signum r)) (pos? (.signum s))
                (neg? (.compareTo r (.getN params)))
                (not (pos? (.compareTo s (.shiftRight (.getN params) 1))))
                (do (.init signer false (ECPublicKeyParameters. (.decodePoint (.getCurve params) public) params))
                    (.verifySignature signer (codec/sha256 message) r s)))))
    (catch Exception _ false)))

(def b58 "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
(defn base58 [bytes]
  (str (apply str (repeat (count (take-while zero? bytes)) \1))
       (loop [n (BigInteger. 1 bytes) result ""]
         (if (zero? (.signum n)) result
             (let [[q r] (.divideAndRemainder n (BigInteger/valueOf 58))]
               (recur q (str (.charAt b58 (.intValue r)) result)))))))
(defn unbase58 [value]
  (when-not (and (string? value) (<= 1 (count value) 200)) (codec/fail! "Invalid base58"))
  (let [n (reduce (fn [^BigInteger acc ch]
                    (let [digit (.indexOf b58 (int ch))]
                      (when (neg? digit) (codec/fail! "Invalid base58"))
                      (.add (.multiply acc (BigInteger/valueOf 58)) (BigInteger/valueOf digit))))
                  BigInteger/ZERO value)]
    (byte-array (concat (repeat (count (take-while #{\1} value)) 0)
                        (if (zero? (.signum n)) [] (BigIntegers/asUnsignedByteArray n))))))
(defn multikey [algorithm public]
  (str "z" (base58 (byte-array (concat (case algorithm "ES256" [128 36] "ES256K" [231 1])
                                         (map #(bit-and 255 %) public))))))
(defn parse-multikey [value]
  (when-not (and (string? value) (str/starts-with? value "z")) (codec/fail! "Invalid multikey"))
  (let [bytes (unbase58 (subs value 1))
        prefix (mapv #(bit-and 255 %) (take 2 bytes))
        algorithm (case prefix [128 36] "ES256" [231 1] "ES256K" (codec/fail! "Unsupported multikey"))]
    (when-not (= 35 (alength bytes)) (codec/fail! "Invalid compressed key length"))
    (let [public (Arrays/copyOfRange bytes 2 35)
          params (domain algorithm)]
      (when-not (#{2 3} (bit-and 255 (aget public 0))) (codec/fail! "Invalid compressed key prefix"))
      ;; Reject infinity and off-curve points at identity parsing time, before a
      ;; malformed signing key can be selected from a DID document.
      (ECPublicKeyParameters. (.decodePoint (.getCurve params) public) params)
      {:algorithm algorithm :public public})))

(defn seal [key purpose plaintext]
  (let [nonce (random-bytes 12) cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init cipher Cipher/ENCRYPT_MODE (SecretKeySpec. key "AES") (GCMParameterSpec. 128 nonce))
    (.updateAAD cipher (codec/utf8 purpose))
    (byte-array (concat nonce (.doFinal cipher plaintext)))))
(defn unseal [key purpose ciphertext]
  (when (< (alength ^bytes ciphertext) 28) (codec/fail! "Invalid encrypted key"))
  (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init cipher Cipher/DECRYPT_MODE (SecretKeySpec. key "AES")
           (GCMParameterSpec. 128 (Arrays/copyOfRange ^bytes ciphertext 0 12)))
    (.updateAAD cipher (codec/utf8 purpose))
    (.doFinal cipher (Arrays/copyOfRange ^bytes ciphertext 12 (alength ^bytes ciphertext)))))

(defn- argon2 [password salt]
  (let [params (-> (Argon2Parameters$Builder. Argon2Parameters/ARGON2_id)
                   (.withVersion Argon2Parameters/ARGON2_VERSION_13)
                   (.withMemoryAsKB 65536) (.withIterations 3) (.withParallelism 1)
                   (.withSalt salt) .build)
        generator (Argon2BytesGenerator.) result (byte-array 32)]
    (.init generator params)
    (.generateBytes generator (codec/utf8 password) result)
    result))
(defn password-hash [password]
  (when-not (and (string? password) (<= 8 (count password) 1024))
    (codec/fail! "Password must contain 8 to 1024 characters"))
  (let [salt (random-bytes 16) encoder (.withoutPadding (Base64/getEncoder))]
    (str "$argon2id$v=19$m=65536,t=3,p=1$" (.encodeToString encoder salt) "$"
         (.encodeToString encoder (argon2 password salt)))))
(defn password-matches? [password encoded]
  (try
    (boolean
     (and (string? password) (<= (count password) 1024)
          (let [[_ salt hash] (re-matches #"\$argon2id\$v=19\$m=65536,t=3,p=1\$([A-Za-z0-9+/]+)\$([A-Za-z0-9+/]+)" encoded)]
            (and salt hash (MessageDigest/isEqual (.decode (Base64/getDecoder) ^String hash)
                                                  (argon2 password (.decode (Base64/getDecoder) ^String salt)))))))
    (catch Exception _ false)))
