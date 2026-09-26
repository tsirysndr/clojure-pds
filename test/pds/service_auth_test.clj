(ns pds.service-auth-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.plc :as plc]
            [pds.protocol.codec :as codec]
            [pds.service-auth :as service-auth])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(defn decode [token]
  (let [[header payload signature] (str/split token #"\.")]
    {:header (json/read-str (codec/text (crypto/unb64 header)))
     :claims (json/read-str (codec/text (crypto/unb64 payload)))
     :message (codec/utf8 (str header "." payload)) :signature (crypto/unb64 signature)}))
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))
(defn upstream! [fixtures]
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [path (Files/createTempFile "pds-service-jwt-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (str path) (json/write-str fixtures))
        (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/verify-service-auth.mjs" (str path)]) (.redirectErrorStream true)))
              finished? (.waitFor process 30 TimeUnit/SECONDS)]
          (when-not finished? (.destroyForcibly process))
          (is finished?)
          (when finished? (is (= 0 (.exitValue process)) (slurp (.getInputStream process)))))
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
