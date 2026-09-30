(ns pds.net-test
  (:require [clojure.test :refer [deftest is]]
            [pds.http :as http]
            [pds.net :as net])
  (:import [java.net InetAddress InetSocketAddress URI]
           [java.nio.file Files]
           [java.security KeyStore]
           [javax.net.ssl KeyManagerFactory SSLContext]
           [com.sun.net.httpserver HttpsServer HttpsConfigurator HttpHandler]
           [org.eclipse.jetty.util Promise SocketAddressResolver]
           [org.eclipse.jetty.util.ssl SslContextFactory$Client]))

(defn resolver [address]
  (reify SocketAddressResolver
    (resolve [_ _ port _ promise]
      (.succeeded ^Promise promise [(InetSocketAddress. (InetAddress/getByName address) port)]))))

(deftest outbound-address-and-url-policy
  (doseq [ip ["0.0.0.0" "127.0.0.1" "10.1.2.3" "100.100.100.200" "169.254.169.254"
              "172.31.255.255" "192.168.1.1" "192.0.2.1" "198.18.0.1" "198.51.100.1" "203.0.113.1"
              "224.0.0.1" "255.255.255.255" "::" "::1" "::ffff:127.0.0.1" "fc00::1" "fe80::1"
              "ff02::1" "2001:db8::1" "2002:7f00:1::1" "64:ff9b::7f00:1" "3fff::1"]]
    (is (false? (net/public-address? (InetAddress/getByName ip))) ip))
  (doseq [ip ["1.1.1.1" "8.8.8.8" "172.32.0.1" "2606:4700:4700::1111" "2001:4860:4860::8888"]]
    (is (true? (net/public-address? (InetAddress/getByName ip))) ip))
  (doseq [url ["http://example.com" "file:///etc/passwd" "https://user:pass@example.com" "https://example.com/#x"
               "https:///path" "https://example.com:0" "https://example.com:99999"]]
    (is (thrown? Exception (net/https-uri! url)) url))
  (is (= "example.com" (.getHost (net/https-uri! "https://example.com/path?query=value")))))

(deftest resolved-addresses-are-used-and-responses-are-bounded
  (let [hits (atom [])
        server (http/start! {:host "127.0.0.1" :port 0}
                 (fn [r]
                   (swap! hits conj (:uri r))
                   (case (:uri r)
                     "/large" {:status 200 :headers {} :body (apply str (repeat 4096 "x"))}
                     "/redirect" {:status 302 :headers {"Location" "/ok"} :body ""}
                     "/loop" {:status 302 :headers {"Location" "/loop"} :body ""}
                     "/encoded" {:status 200 :headers {"Content-Encoding" "gzip"} :body "not-gzip"}
                     {:status 200 :headers {"Set-Cookie" "secret=must-not-reuse"} :body (get-in r [:headers "cookie"] "ok")})))
        url #(str "http://nonexistent.public.example.com:" (:port server) %)
        injected {:resolver (resolver "127.0.0.1") :uri-validator #(URI/create %)}]
    (try
      (with-open [client (net/open-client injected)]
        (is (thrown? Exception (net/fetch! client (url "/blocked")))))
      (is (empty? @hits) "The connector must not reach rejected loopback addresses")
      (with-open [client (net/open-client (assoc injected :address-policy (constantly true)))]
        (is (= "ok" (String. ^bytes (:body (net/fetch! client (url "/ok"))) "UTF-8")))
        (is (= "ok" (String. ^bytes (:body (net/fetch! client (url "/redirect"))) "UTF-8")) "Cookies are not reused")
        (is (thrown? Exception (net/fetch! client (url "/large") {:maximum 128})))
        (is (thrown? Exception (net/fetch! client (url "/loop") {:redirects 1})))
        (is (thrown? Exception (net/fetch! client (url "/encoded")))))
      (finally ((:stop! server))))))

(deftest mixed-dns-and-redirect-destinations-are-rechecked
  (let [hits (atom 0)
        resolved (atom [])
        server (http/start! {:host "127.0.0.1" :port 0}
                 (fn [_] (swap! hits inc)
                   {:status 302 :headers {"Location" "http://private.example.com/secret"} :body ""}))
        delegate (reify SocketAddressResolver
                   (resolve [_ host port _ promise]
                     (swap! resolved conj host)
                     (.succeeded ^Promise promise
                       [(InetSocketAddress. (InetAddress/getByName (if (= host "public.example.com") "127.0.0.1" "127.0.0.2")) port)])))]
    (try
      (with-open [client (net/open-client {:resolver delegate :uri-validator #(URI/create %)
                                          :address-policy #(= "127.0.0.1" (.getHostAddress ^InetAddress %))})]
        (is (thrown? Exception (net/fetch! client (str "http://public.example.com:" (:port server) "/"))))
        (is (= ["public.example.com" "private.example.com"] @resolved))
        (is (= 1 @hits) "Redirect rejected before reaching its destination"))
      (let [result (promise)
            mixed (reify SocketAddressResolver
                    (resolve [_ _ port _ p]
                      (.succeeded ^Promise p (mapv #(InetSocketAddress. (InetAddress/getByName %) port) ["1.1.1.1" "127.0.0.1"])) ))]
        (.resolve (net/guarded-resolver mixed net/public-address?) "mixed.example.com" 443 {}
                  (reify Promise (succeeded [_ _] (deliver result :allowed)) (failed [_ _] (deliver result :blocked))))
        (is (= :blocked (deref result 1000 :timeout)) "A mixed public/private DNS answer is rejected as a whole"))
      (finally ((:stop! server))))))

(defn test-keystore []
  (let [directory (Files/createTempDirectory "pds-net-tls-" (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve directory "server.p12")
        process (.start (doto (ProcessBuilder.
                              [(str (System/getProperty "java.home") "/bin/keytool") "-genkeypair" "-alias" "test"
                               "-keyalg" "EC" "-groupname" "secp256r1" "-dname" "CN=good.example.com"
                               "-ext" "SAN=dns:good.example.com" "-validity" "1" "-storetype" "PKCS12"
                               "-keystore" (str path) "-storepass" "test-password" "-noprompt"])
                          (.redirectErrorStream true)))]
    (try
      (when-not (.waitFor process 20 java.util.concurrent.TimeUnit/SECONDS) (.destroyForcibly process) (throw (ex-info "keytool timeout" {})))
      (when-not (zero? (.exitValue process)) (throw (ex-info (slurp (.getInputStream process)) {})))
      (let [store (KeyStore/getInstance "PKCS12")]
        (with-open [input (Files/newInputStream path (make-array java.nio.file.OpenOption 0))]
          (.load store input (.toCharArray "test-password"))) store)
      (finally (Files/deleteIfExists path) (Files/deleteIfExists directory)))))

(deftest upstream-authentication-challenges-are-not-interpreted
  ;; A 401 or 407 carrying no challenge header is an ordinary refusal to relay,
  ;; not a transport fault: the client must not retry it or reject it outright.
  (let [store (test-keystore)
        manager (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm)) (.init store (.toCharArray "test-password")))
        ssl (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers manager) nil nil))
        server (HttpsServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        trust (doto (SslContextFactory$Client.) (.setTrustStore store))
        status (atom 401)]
    (.setHttpsConfigurator server (HttpsConfigurator. ssl))
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ exchange]
                                   (try (.sendResponseHeaders exchange @status 2)
                                        (.write (.getResponseBody exchange) (.getBytes "no" "UTF-8"))
                                        (finally (.close exchange))))))
    (.start server)
    (try
      (with-open [client (net/open-client {:resolver (resolver "127.0.0.1") :address-policy (constantly true) :ssl-context trust})]
        (let [url (str "https://good.example.com:" (.getPort (.getAddress server)) "/")]
          (is (= 401 (:status (net/fetch! client url))))
          (reset! status 407)
          (is (= 407 (:status (net/fetch! client url))))))
      (finally (.stop server 0)))))

(deftest pinned-dns-preserves-tls-hostname-verification
  (let [store (test-keystore)
        manager (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm)) (.init store (.toCharArray "test-password")))
        ssl (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers manager) nil nil))
        server (HttpsServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        hits (atom 0)
        trust (doto (SslContextFactory$Client.) (.setTrustStore store))]
    (.setHttpsConfigurator server (HttpsConfigurator. ssl))
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ exchange]
                                   (swap! hits inc)
                                   (try (.sendResponseHeaders exchange 200 2)
                                        (.write (.getResponseBody exchange) (.getBytes "ok" "UTF-8"))
                                        (finally (.close exchange))))))
    (.start server)
    (try
      (with-open [client (net/open-client {:resolver (resolver "127.0.0.1") :address-policy (constantly true) :ssl-context trust})]
        (let [port (.getPort (.getAddress server))]
          (is (= 200 (:status (net/fetch! client (str "https://good.example.com:" port "/")))))
          (is (thrown? Exception (net/fetch! client (str "https://wrong.example.com:" port "/"))))
          (is (= 1 @hits) "Wrong hostname fails TLS before an HTTP request")))
      (with-open [client (net/open-client {:resolver (resolver "127.0.0.1") :address-policy (constantly true)})]
        (is (thrown? Exception (net/fetch! client (str "https://good.example.com:" (.getPort (.getAddress server)) "/"))))
        (is (= 1 @hits) "Untrusted certificates are rejected"))
      (finally (.stop server 0)))))
