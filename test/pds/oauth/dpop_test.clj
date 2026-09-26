(ns pds.oauth.dpop-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.jose :as jose]
            [pds.protocol.codec :as codec])
  (:import [java.math BigInteger]
           [java.nio.file Files]
           [java.util Arrays]
           [java.util.concurrent TimeUnit]
           [org.bouncycastle.util BigIntegers]))

(def timestamp 1200000)
(def settings {:master-key (byte-array (range 32)) :public-url "https://pds.example.com"})
(def context {:method "POST" :url "https://pds.example.com/oauth/token"})
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:oauth-error (ex-data e)))))
(defn claims [] {"jti" (crypto/token) "htm" "POST" "htu" (:url context) "iat" (dpop/now) "nonce" (dpop/nonce settings)})
(defn header [key] {"typ" "dpop+jwt" "alg" "ES256" "jwk" (jose/public-jwk (:public key))})
(defn sign-text [key header-text claims-text]
  (let [unsigned (str (crypto/b64 (codec/utf8 header-text)) "." (crypto/b64 (codec/utf8 claims-text)))]
    (str unsigned "." (crypto/b64 (crypto/sign "ES256" (:private key) (codec/utf8 unsigned))))))
(defn sign
  ([key payload] (sign key (header key) payload))
  ([key h payload] (sign-text key (json/write-str h) (json/write-str payload))))
(defn other-s [token]
  (let [[h p sig] (str/split token #"\.") signature (crypto/unb64 sig)
        s (BigInteger. 1 (Arrays/copyOfRange signature 32 64)) n (.getN (crypto/domain "ES256"))]
    (str h "." p "." (crypto/b64 (byte-array (concat (take 32 signature) (BigIntegers/asUnsignedByteArray 32 (.subtract n s))))))))

(deftest public-jwks-and-thumbprints-are-canonical-and-curve-checked
  (let [key (crypto/keypair) jwk (jose/public-jwk (:public key)) parsed (jose/public-key! jwk)]
    (is (= (vec (:public key)) (vec (:public parsed))))
    (is (= (:jkt parsed) (:jkt (jose/public-key! (assoc jwk "kid" "extra" "alg" "ES256" "key_ops" ["verify"] "use" "sig")))))
    (doseq [bad [(assoc jwk "d" "private") (assoc jwk "kty" "RSA") (assoc jwk "crv" "secp256k1")
                 (assoc jwk "use" "enc") (assoc jwk "alg" "HS256") (assoc jwk "key_ops" ["sign"])
                 (assoc jwk "x" (str (get jwk "x") "=")) (dissoc jwk "x")
                 (assoc jwk "x" (crypto/b64 (byte-array 31))) (assoc jwk "y" (crypto/b64 (byte-array 33)))
                 (assoc jwk "x" (crypto/b64 (byte-array 32)) "y" (crypto/b64 (byte-array 32)))
                 (assoc jwk "x" (crypto/b64 (byte-array (repeat 32 -1))))]]
      (is (thrown? Exception (jose/public-key! bad))))))

(deftest nonce-rotation-expiry-origin-and-key-separation
  (with-redefs [dpop/now (constantly timestamp)]
    (let [nonce (dpop/nonce settings)]
      (is (= (+ timestamp 300) (dpop/nonce-expiry settings nonce)))
      (is (= nonce (dpop/nonce (into {} settings))) "A second process needs no memory state")
      (doseq [elapsed [1 119 120 240 299]]
        (with-redefs [dpop/now (constantly (+ timestamp elapsed))]
          (is (= (+ timestamp 300) (dpop/nonce-expiry settings nonce)))
          (is (= (< elapsed 120) (= nonce (dpop/nonce settings))))))
      (doseq [elapsed [-1 300 301 600]]
        (with-redefs [dpop/now (constantly (+ timestamp elapsed))]
          (is (nil? (dpop/nonce-expiry settings nonce)))))
      (doseq [other [(assoc settings :public-url "https://other.example.com")
                    (assoc settings :master-key (byte-array 32))]]
        (is (nil? (dpop/nonce-expiry other nonce))))
      (doseq [bad [nil "" (str nonce "," nonce) (str nonce "=") (str "v1.0" (subs nonce 3))
                   (str/replace nonce #".$" "!") "v1.9999999999999999999999999.x"]]
        (is (nil? (dpop/nonce-expiry settings bad)))))))

(deftest target-normalization-preserves-distinct-resources
  (doseq [[input expected] [["HTTPS://PDS.Example.com:443/a/%7Eb/../%74oken?x=1#part" "https://pds.example.com/a/token"]
                           ["https://pds.example.com" "https://pds.example.com/"]
                           ["http://localhost:80/a" "http://localhost/a"]
                           ["https://pds.example.com:444/a" "https://pds.example.com:444/a"]
                           ["https://pds.example.com/a//b" "https://pds.example.com/a//b"]
                           ["https://pds.example.com/a//../b" "https://pds.example.com/a/b"]
                           ["https://pds.example.com/../../a/." "https://pds.example.com/a/"]
                           ["https://pds.example.com/a%2fb" "https://pds.example.com/a%2Fb"]
                           ["https://[::1]:443/a" "https://[::1]/a"]]]
    (is (= expected (dpop/target-uri input))))
  (doseq [value [nil "" "/relative" "https://user:pass@pds.example.com/a" "file:///a"
                 "https://pds.example.com:0/" "https://pds.example.com/%QQ"]]
    (is (thrown? Exception (dpop/target-uri value)))))

(deftest proof-binds-http-target-token-key-and-freshness
  (with-redefs [dpop/now (constantly timestamp)]
    (let [key (crypto/keypair) payload (claims) token (sign key payload)
          proof (dpop/verify! settings token context) thumbprint (:jkt proof)]
      (is (= thumbprint (:jkt (jose/public-key! (get (header key) "jwk")))))
      (is (= (+ timestamp 300) (:expires-at proof)))
      (is (= (crypto/digest-token (get payload "jti")) (:jti-hash proof)))
      (is (= proof (dpop/verify! settings (other-s token) context)) "JOSE permits high-S signatures")
      (is (= proof (dpop/verify! settings token (assoc context :url (str (:url context) "?a=1&a=2")))))
      (is (= thumbprint (:jkt (dpop/verify! settings token (assoc context :jkt thumbprint)))))
      (doseq [other [(assoc context :method "GET") (assoc context :method "post")
                    (assoc context :url "https://pds.example.com/oauth//token")
                    (assoc context :url "https://pds.example.com/oauth/Token")
                    (assoc context :url "https://pds.example.com:444/oauth/token")
                    (assoc context :url "https://other.example.com/oauth/token")
                    (assoc context :jkt (crypto/token)) (assoc context :access-token "access" :jkt thumbprint)]]
        (is (= "invalid_dpop_proof" (error #(dpop/verify! settings token other)))))
      (let [access "opaque.access-token" with-access (sign key (assoc payload "ath" (crypto/digest-token access)))
            resource (assoc context :access-token access :jkt thumbprint)]
        (is (= thumbprint (:jkt (dpop/verify! settings with-access resource))))
        (is (= "invalid_dpop_proof" (error #(dpop/verify! settings with-access (assoc resource :access-token "different")))))
        (is (= "invalid_dpop_proof" (error #(dpop/verify! settings with-access (dissoc resource :jkt))))))
      (doseq [issued [(- timestamp 300) (+ timestamp 30)]]
        (is (map? (dpop/verify! settings (sign key (assoc payload "iat" issued)) context))))
      (is (= (inc timestamp) (:expires-at (dpop/verify! settings (sign key (assoc payload "exp" (inc timestamp) "nbf" timestamp)) context))))
      (doseq [changes [{"iat" (- timestamp 301)} {"iat" (+ timestamp 31)} {"iat" -1} {"iat" 1.5}
                       {"exp" timestamp} {"exp" nil} {"exp" "1200001"} {"nbf" (inc timestamp)} {"nbf" nil}
                       {"iat" "1200000"} {"iat" nil} {"jti" ""} {"jti" (apply str (repeat 257 "x"))}
                       {"jti" 1} {"htm" "GET"} {"htu" "https://elsewhere.example.com/oauth/token"}]]
        (is (= "invalid_dpop_proof" (error #(dpop/verify! settings (sign key (merge payload changes)) context)))))
      (doseq [missing ["htm" "htu" "jti" "iat"]]
        (is (= "invalid_dpop_proof" (error #(dpop/verify! settings (sign key (dissoc payload missing)) context)))))
      (doseq [nonce [nil "" "forged"]]
        (is (= "use_dpop_nonce" (error #(dpop/verify! settings (sign key (assoc payload "nonce" nonce)) context)))))
      (is (= "use_dpop_nonce" (error #(dpop/verify! settings (sign key (dissoc payload "nonce")) context)))))))

(deftest malformed-jose-never-authenticates-or-triggers-key-fetches
  (with-redefs [dpop/now (constantly timestamp)]
    (let [key (crypto/keypair) payload (claims) h (header key) token (sign key payload)]
      (doseq [changes [{"alg" "none"} {"alg" "HS256"} {"alg" "ES256K"} {"typ" "JWT"} {"typ" nil}
                       {"crit" ["unknown"]} {"b64" false} {"jwk" nil} {"jku" "https://never.example.com/jwks" "jwk" nil}
                       {"jwk" (assoc (get h "jwk") "d" "private")}
                       {"jwk" (jose/public-jwk (:public (crypto/keypair)))}]]
        (is (= "invalid_dpop_proof" (error #(dpop/verify! settings (sign key (merge h changes) payload) context)))))
      (doseq [bad [nil "" "not-a-jwt" (str token "," token) (str token "=") (str token ".more")
                   (str token "\n") (apply str (repeat 16385 "x"))
                   (sign-text key "[]" (json/write-str payload)) (sign-text key (json/write-str h) "[]")
                   (sign-text key (str (json/write-str h) "{}") (json/write-str payload))
                   (sign-text key (json/write-str h) (str (json/write-str payload) "{}"))
                   (sign-text key (json/write-str h) (str (apply str (repeat 65 "[")) "0" (apply str (repeat 65 "]"))))]]
        (is (= "invalid_dpop_proof" (error #(dpop/verify! settings bad context)))))
      (let [[a b _] (str/split token #"\.")]
        (doseq [sig [(byte-array 63) (byte-array 64) (byte-array 65) (byte-array (repeat 64 -1))]]
          (is (= "invalid_dpop_proof" (error #(dpop/verify! settings (str a "." b "." (crypto/b64 sig)) context)))))))))

(deftest independent-node-crypto-signatures-and-thumbprints
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (with-redefs [dpop/now (constantly timestamp)]
      (let [key (crypto/keypair) payload (claims) token (sign key payload)
            path (Files/createTempFile "pds-dpop-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (spit (str path) (json/write-str {:tokens [token (other-s token)] :claims payload
                                          :jkt (:jkt (jose/public-key! (get (header key) "jwk")))}))
          (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/verify-oauth-proofs.mjs" (str path)]) (.redirectErrorStream true)))
                done? (.waitFor process 30 TimeUnit/SECONDS)]
            (when-not done? (.destroyForcibly process))
            (is done?)
            (when done?
              (let [output (slurp (.getInputStream process))]
                (is (= 0 (.exitValue process)) output)
                (when (zero? (.exitValue process))
                  (let [generated (json/read-str output)]
                    (doseq [proof (get generated "tokens")]
                      (is (= (get generated "jkt") (:jkt (dpop/verify! settings proof context))))))))))
          (finally (Files/deleteIfExists path)))))))
