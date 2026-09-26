(ns pds.service-auth-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.identity :as identity]
            [pds.plc :as plc]
            [pds.protocol.codec :as codec]
            [pds.service-auth :as service-auth])
  (:import [java.nio.file Files]
           [java.math BigInteger]
           [java.util Arrays]
           [org.bouncycastle.util BigIntegers]
           [java.util.concurrent TimeUnit]))

(defn decode [token]
  (let [[header payload signature] (str/split token #"\.")]
    {:header (json/read-str (codec/text (crypto/unb64 header)))
     :claims (json/read-str (codec/text (crypto/unb64 payload)))
     :message (codec/utf8 (str header "." payload)) :signature (crypto/unb64 signature)}))
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))
(defn document [issuer key]
  {"id" issuer "verificationMethod" [{"id" "#atproto" "controller" issuer "type" "Multikey"
                                       "publicKeyMultibase" (crypto/multikey (:algorithm key) (:public key))}]})
(defn token-request [token] {:headers {"authorization" (str "Bearer " token)}})
(defn upstream! [fixtures]
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [path (Files/createTempFile "pds-service-jwt-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (str path) (json/write-str fixtures))
        (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/verify-service-auth.mjs" (str path)]) (.redirectErrorStream true)))
              finished? (.waitFor process 30 TimeUnit/SECONDS)]
          (when-not finished? (.destroyForcibly process))
          (is finished?)
          (when finished?
            (let [output (slurp (.getInputStream process))]
              (is (= 0 (.exitValue process)) output)
              (when (zero? (.exitValue process))
                (doseq [fixture (json/read-str output)]
                  (let [issuer (get fixture "issuer")
                        doc (document issuer (plc/parse-key (get fixture "didKey")))
                        resolver (identity/resolver {:fetch (fn [_ _] {:status 200 :body (codec/utf8 (json/write-str doc))})})]
                    (is (= issuer (:did (service-auth/verify! resolver {:service-did "did:web:destination.example.com"}
                                                            (token-request (get fixture "token")) (get fixture "method")))))))))))
        (finally (Files/deleteIfExists path))))))

(deftest audience-syntax-and-expiration-policy
  (doseq [aud ["did:web:example.com" "did:web:localhost" "did:web:localhost%3A3000"
               "did:plc:ewvi7nxzyoun6zhxrhs64oiz" "did:web:example.com#bsky_appview"
               "did:web:example.com#service%20name" "did:web:example.com#a/b?c=d"]]
    (is (service-auth/audience? aud)))
  (doseq [aud [nil "" "example.com" "did:key:z123" "did:plc:bad" "did:web:example.com:somepath"
               "did:web:example.com%3A443" "did:web:localhost%3A65536" "did:web:example.com#"
               "did:web:example.com#a#b" "did:web:example.com#bad fragment" "did:web:example.com#%XX"
               "did:web:example.com/#s" (str "did:web:example.com#" (apply str (repeat 2048 "x")))]]
    (is (not (service-auth/audience? aud))))
  (let [params {"aud" "did:web:example.com#service"} now 1000]
    (is (= {:audience (get params "aud") :method nil :expires 1060} (service-auth/parameters! params now)))
    (is (= 1060 (:expires (service-auth/parameters! (assoc params "exp" "1060") now))))
    (is (= 4600 (:expires (service-auth/parameters! (assoc params "exp" "4600" "lxm" "com.example.method") now))))
    (doseq [exp [nil "" "0" "1000" "999" "-1" "1061" "1.5" "1e3" "NaN" "9223372036854775808"]]
      (is (= "BadExpiration" (error #(service-auth/parameters! (assoc params "exp" exp) now)))))
    (is (= "BadExpiration" (error #(service-auth/parameters! (assoc params "exp" "4601" "lxm" "com.example.method") now))))
    (doseq [lxm [nil "" "*" "bad" "com.example.*"]]
      (is (= "InvalidRequest" (error #(service-auth/parameters! (assoc params "lxm" lxm) now)))))))

(deftest service-tokens-use-repository-signatures-and-separate-jwt-types
  (let [issued (auth/now) issuer "did:web:alice.example.com" aud "did:web:destination.example.com#atproto_pds"
        fixtures (for [algorithm ["ES256" "ES256K"] method [nil "com.atproto.server.createAccount"]]
                   (let [key (crypto/keypair algorithm)
                         token (service-auth/sign key issuer aud method issued (+ issued 60))
                         {:keys [header claims message signature]} (decode token)]
                     (is (= {"typ" "JWT" "alg" algorithm "kid" "#atproto"} header))
                     (is (= issuer (get claims "iss")))
                     (is (= aud (get claims "aud")))
                     (is (= method (get claims "lxm")))
                     (is (= 60 (- (get claims "exp") (get claims "iat"))))
                     (is (string? (get claims "jti")))
                     (is (crypto/verify algorithm (:public key) message signature))
                     (is (not (crypto/verify algorithm (:public (crypto/keypair algorithm)) message signature)))
                     {:token token :issuer issuer :audience aud :method method :didKey (plc/did-key key)}))]
    (upstream! (vec fixtures))))

(defn mint [key header claims]
  (let [unsigned (str (crypto/b64 (codec/utf8 (json/write-str header))) "." (crypto/b64 (codec/utf8 (json/write-str claims))))]
    (str unsigned "." (crypto/b64 (crypto/sign (:algorithm key) (:private key) (codec/utf8 unsigned))))))
(defn high-s [token algorithm]
  (let [[header payload signature] (str/split token #"\.") raw (crypto/unb64 signature)
        s (BigInteger. 1 (Arrays/copyOfRange raw 32 64)) high (.subtract (.getN (crypto/domain algorithm)) s)]
    (str header "." payload "." (crypto/b64 (byte-array (concat (take 32 raw) (BigIntegers/asUnsignedByteArray 32 high)))))))

(deftest receiving-service-jwts-validates-headers-claims-and-signatures
  (let [issuer "did:web:alice.example.com" destination "did:web:destination.example.com"
        method "com.atproto.server.createAccount" settings {:service-did destination} now (auth/now)
        key (crypto/keypair "ES256") header {"typ" "JWT" "alg" "ES256"}
        claims {"iss" issuer "aud" (str destination "#atproto_pds") "lxm" method "iat" now "exp" (+ now 60) "jti" "nonce"}
        doc (atom (document issuer key)) calls (atom 0)
        resolver (identity/resolver {:fetch (fn [_ _] (swap! calls inc) {:status 200 :body (codec/utf8 (json/write-str @doc))})})
        verify #(service-auth/verify! resolver settings (token-request %) method)]
    (doseq [bad-header [(assoc header "typ" "at+jwt") (assoc header "typ" "refresh+jwt") (assoc header "typ" "dpop+jwt")
                        (dissoc header "typ") (assoc header "alg" "none") (assoc header "alg" "HS256")
                        (assoc header "kid" "#atproto_label") (assoc header "kid" (str issuer "#atproto"))
                        (assoc header "crit" ["unknown"]) (assoc header "b64" false)]]
      (is (= "BadJwt" (error #(verify (mint key bad-header claims))))))
    (doseq [bad-claims [(dissoc claims "jti") (assoc claims "jti" "") (assoc claims "jti" (apply str (repeat 257 "a")))
                        (dissoc claims "iat") (assoc claims "iat" (+ now 31)) (assoc claims "exp" (+ now 3601))
                        (assoc claims "exp" "later") (assoc claims "iat" -1) (assoc claims "exp" 1.5)
                        (assoc claims "nbf" (+ now 1)) (assoc claims "iss" (str issuer "#atproto"))]]
      (is (= "BadJwt" (error #(verify (mint key header bad-claims))))))
    (doseq [aud [destination "did:web:other.example.com" (str destination "#other")]]
      (when-not (= aud destination)
        (is (= "BadJwtAudience" (error #(verify (mint key header (assoc claims "aud" aud))))))))
    (doseq [bad-claims [(dissoc claims "lxm") (assoc claims "lxm" "com.atproto.server.getSession")]]
      (is (= "BadJwtLexiconMethod" (error #(verify (mint key header bad-claims))))))
    (is (= "JwtExpired" (error #(verify (mint key header (assoc claims "exp" now "iat" (dec now)))))))
    (is (= 0 @calls) "Invalid headers and claims fail before outbound identity requests")
    (let [token (mint key header claims)]
      (is (= issuer (:did (verify token))))
      (is (= issuer (:did (verify (high-s token "ES256")))) "Service JWT compatibility permits high-S")
      (is (= issuer (:did (verify (mint key (assoc header "kid" "#atproto") (assoc claims "aud" destination))))))
      (is (= "BadJwtSignature" (error #(verify (mint (crypto/keypair "ES256") header claims)))))
      (is (= "BadJwtSignature" (error #(verify (mint key (assoc header "alg" "ES256K") claims)))))
      (reset! doc (document issuer (crypto/keypair "ES256")))
      (is (= "BadJwtSignature" (error #(verify token))) "Fresh resolution rejects the old key after rotation"))))

(deftest incoming-token-bounds-and-expiry-after-resolution
  (let [issuer "did:web:alice.example.com" key (crypto/keypair "ES256K")
        settings {:service-did "did:web:destination.example.com"} method "com.atproto.server.createAccount"
        clock (atom 1000) calls (atom 0)
        resolver (identity/resolver {:fetch (fn [_ _] (swap! calls inc) (reset! clock 1060)
                                              {:status 200 :body (codec/utf8 (json/write-str (document issuer key)))})})]
    (with-redefs [auth/now #(deref clock)]
      (let [token (service-auth/sign key issuer (:service-did settings) method 1000 1060)
            verify #(service-auth/verify! resolver settings (token-request %) method)]
        (doseq [bad [(apply str (repeat 8193 "a")) "a.b.c.d" "a.b.c" "...."]]
          (is (= "BadJwt" (error #(verify bad)))))
        (is (= 0 @calls))
        (is (= "JwtExpired" (error #(verify token))))
        (is (= 1 @calls))))))
