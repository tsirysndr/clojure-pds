(ns pds.oauth.client-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.net :as net]
            [pds.net-test :as net-test]
            [pds.oauth.client :as client]
            [pds.oauth.jose :as jose]
            [pds.oauth.parameters :as parameters]
            [pds.protocol.codec :as codec])
  (:import [java.net InetSocketAddress URI]
           [java.util.concurrent Executors Semaphore]
           [javax.net.ssl KeyManagerFactory SSLContext]
           [com.sun.net.httpserver HttpsServer HttpsConfigurator HttpHandler]
           [org.eclipse.jetty.util.ssl SslContextFactory$Client]))

(def client-id "https://good.example.com/client.json")
(def redirect "https://app.example.com/callback?source=login")
(defn metadata []
  {"client_id" client-id "redirect_uris" [redirect] "grant_types" ["authorization_code" "refresh_token"]
   "scope" "atproto transition:generic" "response_types" ["code"] "dpop_bound_access_tokens" true})
(defn jwk [] (assoc (jose/public-jwk (:public (crypto/keypair))) "kid" (crypto/token)))
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:oauth-error (ex-data e)))))
(defn response [value] {:status 200 :headers {"content-type" "application/json; charset=utf-8"} :body (codec/utf8 (json/write-str value))})
(defn resolve-value [value] (client/resolve! (client/resolver {:oauth-client-fetch (fn [_ _] (response value))}) client-id))

(deftest strict-oauth-parameter-decoding
  (is (= {"scope" "atproto transition:generic" "state" "a+b=c"}
         (parameters/parse! "scope=atproto+transition%3Ageneric&state=a%2Bb%3Dc")))
  (is (= {"a" ["1" "2"]} (parameters/parse! "a=1&a=2" #{"a"})))
  (is (= {"name" "é"} (parameters/parse! "name=%C3%A9")))
  (doseq [bad [nil "a=1&%61=2" "a=%FF" "a=%C0%AF" "a=%D800" "a=%" "a=%XY" "a=raw space"
               "a=1&" "=missing" (str "a=" (apply str (repeat 16384 "x")))
               (str/join "&" (map #(str "x" % "=1") (range 65)))]]
    (is (= "invalid_request" (error #(parameters/parse! bad))))))

(deftest client-profile-and-exact-redirect-policy
  (let [resolver (client/resolver {:oauth-client-fetch (fn [& _] (throw (AssertionError. "Invalid client IDs must not be fetched")))})]
    (doseq [id [nil "" "relative" "https://good.example.com:443/client.json" "https://good.example.com:/client.json"
                "https://user:pass@good.example.com/client.json" "https://good.example.com/client.json#part"
                "http://good.example.com/client.json" "file:///client.json" "https://good.example.com\\@evil.example.com/client.json"]]
      (is (= "invalid_client_metadata" (error #(client/resolve! resolver id))))))
  (let [resolved (resolve-value (metadata))]
    (is (= "none" (get-in resolved [:metadata "token_endpoint_auth_method"])))
    (is (= "web" (get-in resolved [:metadata "application_type"])))
    (is (= redirect (client/redirect! resolved redirect)))
    (doseq [value ["https://app.example.com/callback" "https://app.example.com/callback?source=other"
                   "https://app.example.com/callback?source=login&code=injected" "https://app.example.com:443/callback?source=login"
                   "https://app.example.com.evil/callback?source=login" "https://app.example.com/callback?source=login#fragment"
                   "https://APP.example.com/callback?source=login" "https://app.example.com/%63allback?source=login"]]
      (is (= "invalid_request" (error #(client/redirect! resolved value))))))
  (doseq [field ["client_id" "redirect_uris" "grant_types" "scope" "response_types" "dpop_bound_access_tokens"]]
    (is (= "invalid_client_metadata" (error #(resolve-value (dissoc (metadata) field))))))
  (doseq [[field value] [["client_id" "https://other.example.com/client.json"] ["dpop_bound_access_tokens" false]
                         ["redirect_uris" []] ["redirect_uris" ["http://app.example.com/callback"]]
                         ["redirect_uris" ["https://user:pass@app.example.com/callback"]]
                         ["redirect_uris" ["https://app.example.com/callback#fragment"]]
                         ["grant_types" ["refresh_token"]] ["grant_types" ["authorization_code" "password"]]
                         ["response_types" ["token"]] ["token_endpoint_auth_method" "client_secret_basic"]
                         ["token_endpoint_auth_signing_alg" "none"] ["token_endpoint_auth_signing_alg" "HS256"]
                         ["application_type" "unknown"] ["scope" "transition:generic"] ["scope" "atproto  other"]
                         ["scope" "atproto\nother"] ["client_uri" "https://impostor.example.com"] ["logo_uri" "http://logo.example.com"]
                         ["client_name" 7] ["token_endpoint_auth_method" "private_key_jwt"]]]
    (is (= "invalid_client_metadata" (error #(resolve-value (assoc (metadata) field value)))) (str field " " value)))
  (is (map? (resolve-value (assoc (metadata) "client_uri" "https://good.example.com/home" "logo_uri" "https://cdn.example.com/logo.png")))))

(deftest native-callbacks-bind-to-the-client-origin-or-reverse-hostname
  (doseq [value ["com.example.good:/callback" "https://good.example.com/callback"]]
    (let [resolved (resolve-value (assoc (metadata) "application_type" "native" "redirect_uris" [value]))]
      (is (= value (client/redirect! resolved value)))))
  (doseq [value ["com.example.other:/callback" "com.example.good://callback" "com.example.good:callback"
                 "com.example.good:///callback" "https://other.example.com/callback" "https://good.example.com:444/callback"
                 "http://127.0.0.1/callback" "com.example.good:/callback#fragment"]]
    (is (= "invalid_client_metadata"
           (error #(resolve-value (assoc (metadata) "application_type" "native" "redirect_uris" [value])))) value)))

(deftest localhost-clients-use-virtual-metadata-and-only-loopback-port-exceptions
  (let [resolver (client/resolver {:oauth-client-fetch (fn [& _] (throw (AssertionError. "Localhost metadata must not use the network")))})
        basic (client/resolve! resolver "http://localhost")
        id "http://localhost/?redirect_uri=http%3A%2F%2F127.0.0.1%3A8000%2Fcallback%3Fa%3D1&scope=atproto+transition%3Ageneric"
        custom (client/resolve! resolver id)]
    (is (= "native" (get-in basic [:metadata "application_type"])))
    (is (= "none" (get-in basic [:metadata "token_endpoint_auth_method"])))
    (doseq [value ["http://127.0.0.1:5555/" "http://[::1]:4321/"]]
      (is (= value (client/redirect! basic value))))
    (is (= "http://127.0.0.1:4567/callback?a=1" (client/redirect! custom "http://127.0.0.1:4567/callback?a=1")))
    (doseq [value ["http://localhost:4567/callback?a=1" "http://127.0.0.2:4567/callback?a=1"
                   "http://[::1]:4567/callback?a=1" "http://127.0.0.1:4567/other?a=1"
                   "http://127.0.0.1:4567/callback?a=2" "https://127.0.0.1:4567/callback?a=1"]]
      (is (= "invalid_request" (error #(client/redirect! custom value)))))
    (doseq [id ["http://localhost:80/" "http://127.0.0.1/" "http://localhost/other" "http://localhost/#fragment"
                "http://localhost/?scope=atproto&scope=atproto" "http://localhost/?scope=%FF"
                "http://localhost/?unknown=value" "http://localhost/?redirect_uri=https%3A%2F%2Fevil.example.com%2F"
                "http://localhost/?redirect_uri=http%3A%2F%2Flocalhost%2F"]]
      (is (= "invalid_client_metadata" (error #(client/resolve! resolver id)))))))

(deftest confidential-jwks-refresh-and-invalid-keysets
  (let [key (jwk) calls (atom []) document (atom (assoc (metadata) "token_endpoint_auth_method" "private_key_jwt"
                                                     "jwks_uri" "https://keys.example.com/keys.json"))
        keys (atom {"keys" [key]})
        resolver (client/resolver {:oauth-client-fetch (fn [url options]
                                                        (swap! calls conj [url options])
                                                        (response (if (= url client-id) @document @keys)))})]
    (is (= (:jkt (jose/public-key! key)) (get-in (client/resolve! resolver client-id) [:keys 0 :jkt])))
    (is (= [client-id "https://keys.example.com/keys.json"] (mapv first @calls)))
    (is (every? #(and (= 65536 (:maximum (second %))) (<= 1 (:timeout-ms (second %)) 5000)) @calls))
    (let [replacement (jwk)]
      (reset! keys {"keys" [replacement]})
      (is (= (get replacement "kid") (get-in (client/resolve! resolver client-id) [:keys 0 :kid]))))
    (reset! keys {"keys" []})
    (is (= "invalid_client_metadata" (error #(client/resolve! resolver client-id))))
    (is (= 6 (count @calls)) "Removed keys cannot survive a metadata cache")
    (doseq [bad [nil false {} {"keys" []} {"keys" [key key]} {"keys" [(assoc key "d" "secret")]}
                 {"keys" [(assoc key "x" "malformed")]} {"keys" [(assoc key "alg" "HS256")]}
                 {"keys" (vec (repeat 33 key))}]]
      (is (= "invalid_client_metadata"
             (error #(resolve-value (assoc (metadata) "token_endpoint_auth_method" "private_key_jwt" "jwks" bad))))))
    (is (= "invalid_client_metadata"
           (error #(resolve-value (assoc @document "jwks" {"keys" [key]})))))
    (is (map? (resolve-value (assoc (metadata) "token_endpoint_auth_method" "private_key_jwt" "jwks" {"keys" [key]}))))))

(defn with-server [f]
  (let [store (net-test/test-keystore)
        manager (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm)) (.init store (.toCharArray "test-password")))
        ssl (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers manager) nil nil))
        server (HttpsServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newVirtualThreadPerTaskExecutor)
        responses (atom {"/client.json" (response (metadata))}) calls (atom [])]
    (.setExecutor server executor)
    (.setHttpsConfigurator server (HttpsConfigurator. ssl))
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ exchange]
                                   (try
                                     (swap! calls conj {:uri (str (.getRequestURI exchange))
                                                       :authorization (.getFirst (.getRequestHeaders exchange) "Authorization")
                                                       :cookie (.getFirst (.getRequestHeaders exchange) "Cookie")})
                                     (let [{:keys [status headers body]} (get @responses (.getPath (.getRequestURI exchange))
                                                                            {:status 404 :headers {} :body (byte-array 0)})]
                                       (doseq [[k v] headers] (.set (.getResponseHeaders exchange) k v))
                                       (.sendResponseHeaders exchange status (alength ^bytes body))
                                       (.write (.getResponseBody exchange) body))
                                     (finally (.close exchange))))))
    (.start server)
    ;; Client IDs cannot have a port. Only the fixture transport maps its HTTPS
    ;; socket to an ephemeral port; the metadata URL and TLS hostname stay bound.
    (let [options {:resolver (net-test/resolver "127.0.0.1") :address-policy (constantly true)
                   :uri-validator (fn [value]
                                    (let [uri (net/https-uri! value)]
                                      (URI. "https" nil (.getHost uri) (.getPort (.getAddress server))
                                            (.getPath uri) (.getQuery uri) nil)))
                   :ssl-context (doto (SslContextFactory$Client.) (.setTrustStore store))}]
      (try (with-open [http (net/open-client options)]
          (f {:resolver (client/resolver {:http-client http}) :responses responses :calls calls :options options}))
           (finally (.stop server 0) (.shutdownNow executor))))))

(deftest metadata-and-jwks-use-bounded-credential-free-https-without-redirects
  (with-server
    (fn [{:keys [resolver responses calls options]}]
      (let [key (jwk)]
        (swap! responses assoc "/client.json" (response (assoc (metadata) "token_endpoint_auth_method" "private_key_jwt" "jwks_uri" "https://good.example.com/keys.json"))
               "/keys.json" (assoc-in (response {"keys" [key]}) [:headers "set-cookie"] "credential=do-not-reuse"))
        (is (= (get key "kid") (get-in (client/resolve! resolver client-id) [:keys 0 :kid])))
        (is (= (get key "kid") (get-in (client/resolve! resolver client-id) [:keys 0 :kid])))
        (is (every? #(and (nil? (:authorization %)) (nil? (:cookie %))) @calls))
        (doseq [bad [{:status 302 :headers {"location" "https://good.example.com/followed"} :body (codec/utf8 "redirect")}
                     (assoc (response (metadata)) :status 201)
                     (assoc-in (response (metadata)) [:headers "content-type"] "text/html")
                     (assoc-in (response (metadata)) [:headers "content-encoding"] "gzip")
                     {:status 200 :headers {"content-type" "application/json"} :body (byte-array 65537)}
                     {:status 200 :headers {"content-type" "application/json"} :body (codec/utf8 "{}{}")}
                     (response [])]]
          (swap! responses assoc "/client.json" bad)
          (let [before (count @calls)]
            (is (= "invalid_client_metadata" (error #(client/resolve! resolver client-id))))
            (is (= (inc before) (count @calls)) "Redirect destinations must not be visited")))
        (let [before (count @calls)]
          (is (= "invalid_client_metadata" (error #(client/resolve! resolver "https://wrong.example.com/client.json"))))
          (is (= before (count @calls)) "Hostname mismatch fails before HTTP")
          (with-open [guarded (net/open-client (dissoc options :address-policy))]
            (is (= "invalid_client_metadata" (error #(client/resolve! (client/resolver {:http-client guarded}) client-id))))
            (is (= before (count @calls)) "Production DNS policy rejects private socket addresses")))
        (let [permits ^Semaphore (:permits resolver)]
          (.acquire permits 16)
          (try (is (= "temporarily_unavailable" (error #(client/resolve! resolver client-id))))
               (finally (.release permits 16)))
          (swap! responses assoc "/client.json" (response (metadata)))
          (is (map? (client/resolve! resolver client-id)))
          (is (= 16 (.availablePermits permits)) "Failures always release the resolver permit"))))))
