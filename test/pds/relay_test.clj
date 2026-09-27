(ns pds.relay-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.net :as net]
            [pds.net-test :as network]
            [pds.relay :as relay])
  (:import [java.net InetSocketAddress]
           [java.time Instant]
           [javax.net.ssl KeyManagerFactory SSLContext]
           [com.sun.net.httpserver HttpsServer HttpsConfigurator HttpHandler]
           [org.eclipse.jetty.util.ssl SslContextFactory$Client]))

(deftest opt-in-configuration-and-origins
  (is (= {:relay-urls [] :relay-interval-seconds 1200} (relay/settings {})))
  (is (= ["https://relay.example.com" "https://other.example.com:8443"]
         (:relay-urls (relay/settings {"PDS_RELAY_URLS" " https://Relay.example.com/,https://relay.example.com:443,https://other.example.com:8443"}))))
  (doseq [value ["http://relay.example.com" "https://user:secret@relay.example.com" "https://relay.example.com/api"
                 "https://relay.example.com?key=secret" "https://relay.example.com/#fragment" "," "https://relay.example.com,"
                 (clojure.string/join "," (repeat 17 "https://relay.example.com"))]]
    (is (thrown? clojure.lang.ExceptionInfo (relay/settings {"PDS_RELAY_URLS" value}))))
  (doseq [value ["0" "59" "86401" "false"]]
    (is (thrown? clojure.lang.ExceptionInfo (relay/settings {"PDS_RELAY_INTERVAL_SECONDS" value}))))
  (is (= "pds.example.com" (relay/hostname! {:public-url "https://pds.example.com:443/"})))
  (doseq [url ["http://localhost:3000" "https://pds.example.com:8443" "https://pds.example.com/path" nil]]
    (is (thrown? clojure.lang.ExceptionInfo (relay/hostname! {:public-url url}))))
  (is (nil? ((relay/start! nil {})))))

(deftest bounded-retry-after
  (let [now (Instant/parse "2026-09-27T00:00:00Z")]
    (doseq [[text expected] [["0" 5] ["60" 60] ["9999999999" 86400]
                            ["Sun, 27 Sep 2026 00:02:00 GMT" 120]
                            ["Sat, 26 Sep 2026 00:00:00 GMT" 5]
                            ["bad" nil] [nil nil] ["-1" nil]]]
      (is (= expected (relay/retry-after text now))))))

(deftest relay-requests-over-real-tls
  (let [store (network/test-keystore)
        manager (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm)) (.init store (.toCharArray "test-password")))
        ssl (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers manager) nil nil))
        server (HttpsServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        url (str "https://good.example.com:" (.getPort (.getAddress server)))
        mode (atom :ok) seen (atom [])]
    (.setHttpsConfigurator server (HttpsConfigurator. ssl))
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (swap! seen conj {:method (.getRequestMethod exchange) :path (.getPath (.getRequestURI exchange))
                             :auth (.getFirst (.getRequestHeaders exchange) "Authorization")
                             :cookie (.getFirst (.getRequestHeaders exchange) "Cookie")
                             :content-type (.getFirst (.getRequestHeaders exchange) "Content-Type")
                             :body (json/read-str (String. (.readAllBytes (.getRequestBody exchange)) "UTF-8"))})
            (.set (.getResponseHeaders exchange) "Set-Cookie" "secret=ignored")
            (when (= :redirect @mode) (.set (.getResponseHeaders exchange) "Location" (str url "/not-followed")))
            (when (= :encoded @mode) (.set (.getResponseHeaders exchange) "Content-Encoding" "gzip"))
            (let [body (if (= :large @mode) (byte-array 65537) (.getBytes "{}" "UTF-8"))]
              (.sendResponseHeaders exchange (if (= :redirect @mode) 302 200) (alength body))
              (.write (.getResponseBody exchange) body))
            (catch java.io.IOException _)
            (finally (.close exchange))))))
    (.start server)
    (try
      (with-open [client (net/open-client {:resolver (network/resolver "127.0.0.1") :address-policy (constantly true)
                                          :ssl-context (doto (SslContextFactory$Client.) (.setTrustStore store))})]
        (let [job {:relay_url url :hostname "pds.example.com"} settings {:http-client client}]
          (dotimes [_ 2] (is (:success? (relay/deliver! settings job))))
          (reset! mode :redirect)
          (is (= {:success? false :status 302 :error "http-error" :retry-after nil} (relay/deliver! settings job)))
          (doseq [behavior [:encoded :large]]
            (reset! mode behavior)
            (is (= {:success? false :error "transport-error"} (relay/deliver! settings job))))
          (is (= 5 (count @seen)))
          (is (every? #(= {:method "POST" :path "/xrpc/com.atproto.sync.requestCrawl" :auth nil :cookie nil
                           :content-type "application/json" :body {"hostname" "pds.example.com"}} %) @seen))))
      (finally (.stop server 0)))))
