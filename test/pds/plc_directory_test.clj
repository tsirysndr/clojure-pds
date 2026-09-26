(ns pds.plc-directory-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.net :as net]
            [pds.net-test :as net-test]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-test :as plc-test]
            [pds.protocol.codec :as codec]
            [pds.request :as request])
  (:import [java.net InetSocketAddress]
           [java.time Instant]
           [javax.net.ssl KeyManagerFactory SSLContext]
           [com.sun.net.httpserver HttpsServer HttpsConfigurator HttpHandler]
           [org.eclipse.jetty.util.ssl SslContextFactory$Client]))

(defn with-directory [f]
  (let [store (net-test/test-keystore)
        manager (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm)) (.init store (.toCharArray "test-password")))
        ssl (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers manager) nil nil))
        server (HttpsServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        logs (atom {}) mode (atom :accept) audit-body (atom nil) calls (atom [])]
    (.setHttpsConfigurator server (HttpsConfigurator. ssl))
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [path (.getPath (.getRequestURI exchange))
                  method (.getRequestMethod exchange)
                  did (second (clojure.string/split path #"/"))
                  _ (swap! calls conj [method path])
                  [status body]
                  (if (= "GET" method)
                    (cond @audit-body [200 @audit-body]
                          (get @logs did) [200 (json/write-str (get @logs did))]
                          :else [404 "{}"])
                    (do
                      (is (= "application/json" (.getFirst (.getRequestHeaders exchange) "Content-Type")))
                      (let [op (request/json-value (.readNBytes (.getRequestBody exchange) 65537))]
                        (when (#{:accept :accept-drop :accept-error} @mode)
                          (plc/verify-log! did (conj (mapv #(get % "operation") (get @logs did [])) op))
                          (swap! logs update did (fnil conj []) (plc-test/row did op (str (Instant/now)) false))))
                      (case @mode :accept [200 "{}"] :accept-error [503 "private upstream details"]
                        :accept-drop [nil nil] :ignore [200 "{}"] :reject [400 "private upstream details"]
                        :redirect (do (.set (.getResponseHeaders exchange) "Location" "https://other.example.com/never") [307 ""])) ))]
              (when status
                (let [bytes (codec/utf8 body)]
                  (.sendResponseHeaders exchange status (alength bytes))
                  (.write (.getResponseBody exchange) bytes))))
            (finally (.close exchange))))))
    (.start server)
    (try
      (with-open [client (net/open-client {:resolver (net-test/resolver "127.0.0.1") :address-policy (constantly true)
                                          :ssl-context (doto (SslContextFactory$Client.) (.setTrustStore store))})]
        (f {:client client :origin (str "https://good.example.com:" (.getPort (.getAddress server)))
            :logs logs :mode mode :audit-body audit-body :calls calls}))
      (finally (.stop server 0)))))

(defn failure [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))
(defn creation []
  (let [key (crypto/keypair "ES256") op (plc/sign-operation (plc-test/unsigned [key] key) key)]
    {:key key :op op :did (plc/genesis-did op)}))
(defn posts [calls] (filter #(= "POST" (first %)) @calls))

(deftest submit-confirm-and-retry-with-real-tls
  (with-directory
    (fn [{:keys [client origin calls logs]}]
      (let [{:keys [key op did]} (creation)
            result (directory/ensure-operation! client origin did op)]
        (is (= (plc/operation-cid op) (:head result)))
        (is (= 1 (count (posts calls))))
        (is (= result (directory/ensure-operation! client origin did op)))
        (is (= 1 (count (posts calls))) "Already accepted operations are not submitted again")
        (let [next (plc-test/update-op op key {"alsoKnownAs" ["at://updated.example.com"]})]
          (is (= (plc/operation-cid next) (:head (directory/ensure-operation! client origin did next))))
          (is (= 2 (count (get @logs did))))
          (is (= :conflict (:reason (failure #(directory/ensure-operation! client origin did op)))))
          (is (= 2 (count (posts calls))) "Old genesis cannot replace a later head")
          (let [bad (plc-test/update-op next (crypto/keypair "ES256") {})]
            (is (thrown? Exception (directory/ensure-operation! client origin did bad)))
            (is (= 2 (count (posts calls))) "Unauthorized updates never reach POST")))))))

(deftest ambiguous-submissions-are-reconciled-before-success
  (with-directory
    (fn [{:keys [client origin mode calls logs]}]
      (doseq [behavior [:accept-drop :accept-error]]
        (reset! mode behavior)
        (let [{:keys [op did]} (creation)]
          (is (= (plc/operation-cid op) (:head (directory/ensure-operation! client origin did op))))
          (is (= 1 (count (get @logs did))))))
      (let [before (count (posts calls)) {:keys [op did]} (creation)]
        (reset! mode :ignore)
        (is (= {:type :plc-directory :reason :unconfirmed :retryable true}
               (failure #(directory/ensure-operation! client origin did op))))
        (is (nil? (get @logs did)))
        (reset! mode :accept)
        (is (= (plc/operation-cid op) (:head (directory/ensure-operation! client origin did op))))
        (is (= (+ before 2) (count (posts calls))) "Unconfirmed retry submits the identical operation")))))

(deftest directory-rejections-and-invalid-audits-are-not-success
  (with-directory
    (fn [{:keys [client origin mode audit-body calls]}]
      (doseq [[behavior reason] [[:reject :rejected] [:redirect :redirect]]]
        (reset! mode behavior)
        (let [{:keys [op did]} (creation)]
          (is (= {:type :plc-directory :reason reason :retryable false}
                 (failure #(directory/ensure-operation! client origin did op))))))
      (is (= 2 (count (posts calls))) "POST redirects are not followed")
      (doseq [body ["[]" "{}" "[]{}" "{invalid" (str (apply str (repeat 65 "[")) "0" (apply str (repeat 65 "]")))]]
        (reset! audit-body body)
        (let [{:keys [op did]} (creation)]
          (is (= :invalid-audit (:reason (failure #(directory/ensure-operation! client origin did op)))))))
      (is (= 2 (count (posts calls))) "An invalid audit blocks all submission")
      (is (= :invalid-did (:reason (failure #(directory/audit! client origin "did:web:example.com"))))))))
