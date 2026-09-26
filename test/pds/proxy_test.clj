(ns pds.proxy-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pds.identity :as identity]
            [pds.net :as net]
            [pds.net-test :as net-test]
            [pds.protocol.codec :as codec]
            [pds.proxy :as proxy]
            [pds.xrpc :as xrpc])
  (:import [java.net InetSocketAddress]
           [java.util.concurrent Executors]
           [javax.net.ssl KeyManagerFactory SSLContext]
           [com.sun.net.httpserver HttpsServer HttpsConfigurator HttpHandler]
           [org.eclipse.jetty.util.ssl SslContextFactory$Client]))

(def service-did "did:web:service.example.com")
(def audience (str service-did "#custom_service"))
(defn document [origin]
  {"id" service-did "service" [{"id" "#custom_service" "type" "CustomService" "serviceEndpoint" origin}]})

(defn with-service [f]
  (let [store (net-test/test-keystore)
        manager (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm)) (.init store (.toCharArray "test-password")))
        ssl (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers manager) nil nil))
        server (HttpsServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newVirtualThreadPerTaskExecutor)
        mode (atom :ok) calls (atom []) doc (atom nil) on-resolve (atom nil) on-request (atom nil)]
    (.setExecutor server executor)
    (.setHttpsConfigurator server (HttpsConfigurator. ssl))
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [path (.getPath (.getRequestURI exchange))
                  did? (= "/did" path)
                  method (.getRequestMethod exchange)
                  _ (when did? (when-let [hook @on-resolve] (hook)))
                  _ (when-not did?
                      (swap! calls conj {:method method :path path :query (.getRawQuery (.getRequestURI exchange))
                                         :headers (into {} (map (fn [[k v]] [(str/lower-case k) (str/join "," v)])) (.getRequestHeaders exchange))
                                         :body (vec (.readNBytes (.getRequestBody exchange) 1048577))}))
                  _ (when-not did? (when-let [hook @on-request] (hook)))
                  [status type text] (if did? [200 "application/json" (json/write-str @doc)]
                                      (case @mode
                                        :redirect [307 "text/plain" "redirect"]
                                        :error [429 "application/json" "{\"error\":\"RateLimitExceeded\",\"message\":\"Slow down\"}"]
                                        :html-error [500 "text/html" "<b>private upstream failure</b>"]
                                        :large [200 "application/json" (apply str (repeat 8192 "x"))]
                                        :drop [nil nil nil]
                                        [200 "application/json" "{\"ok\":true}"]))]
              (when status
                (when-not did?
                  (doseq [[k v] {"Set-Cookie" "remote=must-not-reuse" "Location" "https://elsewhere.example.com/never"
                                 "Atproto-Repo-Rev" "2222222222222" "Atproto-Content-Labelers" service-did "Retry-After" "3"}]
                    (.set (.getResponseHeaders exchange) k v))
                  (when (= :encoded @mode) (.set (.getResponseHeaders exchange) "Content-Encoding" "gzip")))
                (.set (.getResponseHeaders exchange) "Content-Type" type)
                (let [bytes (codec/utf8 text)]
                  (if (= "HEAD" method)
                    (do (.set (.getResponseHeaders exchange) "Content-Length" (str (alength bytes)))
                        (.sendResponseHeaders exchange status -1))
                    (do (.sendResponseHeaders exchange status (alength bytes))
                        (.write (.getResponseBody exchange) bytes))))))
            (finally (.close exchange))))))
    (.start server)
    (let [origin (str "https://good.example.com:" (.getPort (.getAddress server)))]
      (reset! doc (document origin))
      (try
        (with-open [client (net/open-client {:resolver (net-test/resolver "127.0.0.1") :address-policy (constantly true)
                                            :ssl-context (doto (SslContextFactory$Client.) (.setTrustStore store))})]
          (f {:client client :origin origin :doc doc :mode mode :calls calls :on-resolve on-resolve :on-request on-request
              :fetch (fn [_ options] (net/fetch! client (str origin "/did") options))}))
        (finally (.stop server 0) (.shutdownNow executor))))))

(deftest rejects-invalid-config-and-ambiguous-services
  (is (= 16 (:proxy-max-concurrent (proxy/settings {}))))
  (is (= audience (:proxy-appview-service (proxy/settings {"PDS_APPVIEW_SERVICE" audience}))))
  (doseq [[key value] [["PDS_APPVIEW_SERVICE" service-did] ["PDS_LABELER_SERVICE" "https://example.com"]
                       ["PDS_PROXY_TIMEOUT_MS" "60001"] ["PDS_PROXY_MAX_CONCURRENT" "0"]
                       ["PDS_PROXY_MAX_REQUEST_BYTES" "67108865"] ["PDS_PROXY_MAX_RESPONSE_BYTES" "bad"]]]
    (is (thrown? Exception (proxy/settings {key value}))))
  (doseq [value [nil "" service-did (str audience "#extra") "did:web:localhost#s" "did:web:example.com#"]]
    (is (thrown? Exception (proxy/service! value))))
  (let [good (document "https://service.example.com")
        resolve #(proxy/destination! (identity/resolver {:local-document (constantly %)}) (proxy/service! audience))]
    (is (= "https://service.example.com" (resolve good)))
    (is (= "https://service.example.com" (resolve (assoc-in good ["service" 0 "id"] audience))))
    (doseq [bad [(assoc good "service" []) (update good "service" #(conj % (first %)))
                 (assoc-in good ["service" 0 "serviceEndpoint"] "http://service.example.com")
                 (assoc-in good ["service" 0 "serviceEndpoint"] "https://service.example.com/path")
                 (assoc-in good ["service" 0 "serviceEndpoint"] {"url" "https://service.example.com"})]]
      (is (thrown? Exception (resolve bad)))))
  (is (= {"x-atproto-test" "yes" "accept-language" "en"}
         (proxy/request-headers {:headers {"authorization" "session" "cookie" "secret" "host" "local"
                                           "atproto-proxy" audience "x-forwarded-for" "client" "connection" "x-atproto-hop, content-type"
                                           "content-type" "text/plain"
                                           "x-atproto-hop" "no" "x-atproto-test" "yes" "accept-language" "en"}}))))

(deftest bounded-exchanges-do-not-follow-redirects-or-retry-posts
  (with-service
    (fn [{:keys [client origin mode calls]}]
      (let [url (str origin "/xrpc/com.example.test") opts {:method "POST" :body (byte-array [1 2 3])
                                                          :headers {"authorization" "Bearer scoped" "content-type" "application/octet-stream"}}]
        (is (= 200 (:status (net/exchange! client url opts))))
        (is (= [1 2 3] (:body (last @calls))))
        (reset! mode :redirect)
        (is (= 307 (:status (net/exchange! client url opts))))
        (is (= 2 (count @calls)))
        (reset! mode :drop)
        (is (thrown? Exception (net/exchange! client url opts))))
      (is (= 3 (count @calls)))
      (is (every? #(nil? (get-in % [:headers "cookie"])) @calls)))))

(deftest unsupported-proxy-transports-and-paths-never-reach-authentication
  (let [handler (xrpc/router {} (proxy/handler nil {:proxy-appview-service audience}))
        request {:uri "/xrpc/com.example.read" :request-method :get :headers {}}]
    (doseq [headers [{"upgrade" "WebSocket"} {"upgrade" "h2c, websocket"} {"dpop" "proof"}]]
      (is (= 400 (:status (handler (assoc request :headers headers))))))
    (let [response (handler (assoc request :request-method :delete))]
      (is (= 405 (:status response)))
      (is (= "GET, HEAD, POST" (get-in response [:headers "Allow"]))))
    (doseq [uri ["/other" "/xrpc/invalid" "/xrpc/com.example.read/extra" "/xrpc/com.example.%72ead"]]
      (is (= 404 (:status (handler (assoc request :uri uri))))))
    (is (= 404 (:status ((xrpc/router {} (proxy/handler nil {})) request))))))
