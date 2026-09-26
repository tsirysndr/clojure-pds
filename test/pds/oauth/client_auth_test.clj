(ns pds.oauth.client-auth-test
  (:require [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.oauth.client :as client]
            [pds.oauth.client-auth :as auth]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof]
            [pds.oauth.jose :as jose]))

(def settings {:public-url "https://pds.example.com"})
(defn new-key [] (assoc (crypto/keypair) :kid (crypto/token)))
(defn document [keys]
  (assoc (metadata/metadata) "token_endpoint_auth_method" "private_key_jwt"
         "jwks" {"keys" (mapv #(assoc (jose/public-jwk (:public %)) "kid" (:kid %)) keys)}))
(defn resolved [keys] (metadata/resolve-value (document keys)))
(defn claims [] {"iss" metadata/client-id "sub" metadata/client-id "aud" (:public-url settings)
                "iat" (dpop/now) "exp" (+ (dpop/now) 120) "jti" (crypto/token)})
(defn header [key] {"alg" "ES256" "typ" "JWT" "kid" (:kid key)})
(defn params
  ([key] (params key (claims)))
  ([key payload] (params key (header key) payload))
  ([key h payload]
   {"client_id" metadata/client-id "client_assertion_type" auth/assertion-type "client_assertion" (proof/sign key h payload)}))
(defn error [f] (proof/error f))

(deftest public-client-authentication-does-not-upgrade-to-confidential
  (let [client (metadata/resolve-value (metadata/metadata))
        expected {:client-id metadata/client-id :method "none"}]
    (is (= {:binding expected} (auth/verify! client settings {"client_id" metadata/client-id})))
    (is (auth/binding-current? client expected))
    (doseq [input [(params (new-key)) {"client_id" metadata/client-id "client_assertion_type" auth/assertion-type}
                   {"client_id" metadata/client-id "client_secret" ""} {"client_id" "another"} {}]]
      (is (= "invalid_client" (error #(auth/verify! client settings input)))))))

(deftest private-key-assertions-authenticate-the-published-key
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [key (new-key) client (resolved [key]) input (params key)
          candidate (auth/verify! client settings input)
          binding {:client-id metadata/client-id :method "private_key_jwt" :kid (:kid key) :alg "ES256"
                   :jkt (:jkt (jose/public-key! (jose/public-jwk (:public key))))}]
      (is (= binding (:binding candidate)))
      (is (= (+ proof/timestamp 120) (:expires-at candidate)))
      (is (auth/binding-current? client binding))
      (is (= candidate (auth/verify! client settings input binding)))
      (is (= candidate (auth/verify! client settings (update input "client_assertion" proof/other-s))))
      (is (= binding (:binding (auth/verify! client settings (params key (dissoc (header key) "typ") (claims))))))
      (is (= binding (:binding (auth/verify! client settings (params key (assoc (claims) "aud" ["https://other.example.com" (:public-url settings)]))))))
      (doseq [input [(dissoc input "client_assertion_type") (assoc input "client_assertion_type" "unsupported")
                     (dissoc input "client_assertion") (assoc input "client_assertion" "")
                     (assoc input "client_secret" "secret") (assoc input "client_id" "https://other.example.com")]]
        (is (= "invalid_client" (error #(auth/verify! client settings input)))))
      (doseq [h [(dissoc (header key) "kid") (assoc (header key) "kid" "unknown")
                 (assoc (header key) "typ" "dpop+jwt") (assoc (header key) "alg" "HS256")
                 (assoc (header key) "crit" ["new-extension"])]]
        (is (= "invalid_client" (error #(auth/verify! client settings (params key h (claims)))))))
      (let [attacker (new-key)]
        (is (= "invalid_client"
               (error #(auth/verify! client settings
                                    (params attacker (assoc (header key) "jwk" (jose/public-jwk (:public attacker))) (claims)))))))
      (doseq [field ["iss" "sub" "aud" "iat" "exp" "jti"]]
        (is (= "invalid_client" (error #(auth/verify! client settings (params key (dissoc (claims) field))))))))))

(deftest claim-lifetime-and-audience-boundaries
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [key (new-key) client (resolved [key])]
      (doseq [issued [(- proof/timestamp 60) (dec proof/timestamp) proof/timestamp (+ proof/timestamp 30)]]
        (is (map? (auth/verify! client settings (params key (assoc (claims) "iat" issued))))))
      (doseq [changes [{"iss" "https://other.example.com"} {"sub" "https://other.example.com"}
                       {"aud" (str (:public-url settings) "/oauth/token")} {"aud" []} {"aud" ["wrong"]}
                       {"aud" [(:public-url settings) nil]} {"aud" nil}
                       {"iat" (- proof/timestamp 301)} {"iat" (+ proof/timestamp 31)} {"iat" "1200000"}
                       {"exp" proof/timestamp} {"exp" (+ proof/timestamp 301)} {"exp" "1200120"} {"exp" nil}
                       {"jti" ""} {"jti" (apply str (repeat 257 "x"))} {"jti" 1}
                       {"nbf" (inc proof/timestamp)} {"nbf" "1200000"} {"nbf" nil}]]
        (is (= "invalid_client" (error #(auth/verify! client settings (params key (merge (claims) changes))))) (pr-str changes)))
      (is (map? (auth/verify! client settings (params key (assoc (claims) "nbf" proof/timestamp))))))))

(deftest bindings-prevent-key-substitution-and-authentication-downgrades
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [first-key (new-key) next-key (new-key) client (resolved [first-key next-key])
          first-input (params first-key) binding (:binding (auth/verify! client settings first-input))]
      (is (map? (auth/verify! client settings (params next-key))))
      (is (= "invalid_client" (error #(auth/verify! client settings (params next-key) binding))))
      (let [removed (resolved [next-key]) replacement (assoc next-key :kid (:kid first-key))
            renamed (assoc first-key :kid (:kid next-key))]
        (doseq [updated [(resolved [replacement]) (resolved [renamed]) removed (metadata/resolve-value (metadata/metadata))]]
          (is (false? (auth/binding-current? updated binding))))
        (is (= "invalid_client" (error #(auth/verify! (resolved [replacement]) settings (params replacement) binding))))
        (is (= "invalid_client" (error #(auth/verify! (resolved [renamed]) settings (params renamed) binding))))
        (is (= "invalid_client" (error #(auth/verify! removed settings first-input binding)))))
      (is (= "invalid_client"
             (error #(auth/verify! client settings first-input (assoc binding :client-id "https://other.example.com"))))))))

(deftest live-jwks-removal-is-visible-before-client-authentication
  (metadata/with-server
    (fn [{:keys [resolver responses calls]}]
      (let [key (new-key) replacement (new-key)
            doc (-> (document [key]) (dissoc "jwks") (assoc "jwks_uri" "https://good.example.com/keys.json"))]
        (swap! responses assoc "/client.json" (metadata/response doc)
               "/keys.json" (metadata/response (get (document [key]) "jwks")))
        (let [first-client (client/resolve! resolver metadata/client-id)
              binding (:binding (auth/verify! first-client settings (params key)))]
          (swap! responses assoc "/keys.json" (metadata/response (get (document [replacement]) "jwks")))
          (let [refreshed (client/resolve! resolver metadata/client-id)]
            (is (= 4 (count @calls)))
            (is (false? (auth/binding-current? refreshed binding)))
            (is (= "invalid_client" (error #(auth/verify! refreshed settings (params key) binding))))
            (is (= "invalid_client" (error #(auth/verify! refreshed settings (params replacement) binding))))
            (is (map? (auth/verify! refreshed settings (params replacement))))))))))
