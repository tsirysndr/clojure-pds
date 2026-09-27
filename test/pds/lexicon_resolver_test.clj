(ns pds.lexicon-resolver-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.identity :as identity]
            [pds.lexicon-resolver :as resolver]
            [pds.net :as net]
            [pds.net-test :as net-test]
            [pds.protocol.codec :as codec]
            [pds.record-proof-test :as proofs]
            [pds.repository-test :as repository])
  (:import [java.net InetSocketAddress]
           [javax.net.ssl KeyManagerFactory SSLContext]
           [com.sun.net.httpserver HttpsServer HttpsConfigurator HttpHandler]
           [org.eclipse.jetty.util.ssl SslContextFactory$Client]))

(def nsid "com.example.authBasic")
(def path (str "com.atproto.lexicon.schema/" nsid))
(def schema {"$type" "com.atproto.lexicon.schema" "lexicon" 1 "id" nsid
             "defs" {"main" {"type" "permission-set" "title" "Basic access" "permissions" []}}})
(defn document [key endpoint]
  {"id" repository/did
   "verificationMethod" [{"id" "#atproto" "controller" repository/did "type" "Multikey"
                           "publicKeyMultibase" (crypto/multikey (:algorithm key) (:public key))}]
   "service" [{"id" "#atproto_pds" "type" "AtprotoPersonalDataServer" "serviceEndpoint" endpoint}]})
(defn fixture []
  (let [key (crypto/keypair) doc (atom (document key "https://pds.example.net"))
        records (atom [(str "did=" repository/did)]) calls (atom [])
        response (atom {:status 200 :body (proofs/slice (repository/fixture key {path schema}) path)})
        identity (identity/resolver {:local-document (fn [did] (is (= repository/did did)) @doc)})
        resolver (resolver/resolver {:identity-resolver identity
                                     :txt-lookup (fn [name] (swap! calls conj [:dns name]) @records)
                                     :fetch (fn [url opts] (swap! calls conj [:fetch url opts]) @response)})]
    {:key key :doc doc :records records :calls calls :response response :resolver resolver}))
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:lexicon-error (ex-data e)))))

(deftest exact-authority-signed-schema-and-provenance
  (let [{:keys [resolver records calls]} (fixture)
        result (resolver/resolve! resolver nsid)]
    (is (= schema (:schema result)))
    (is (= repository/did (:did result)))
    (is (string? (:cid result)))
    (is (string? (:head result)))
    (is (= [[:dns "_lexicon.example.com."]
            [:fetch "https://pds.example.net/xrpc/com.atproto.sync.getRecord?did=did%3Aweb%3Asource.example.net&collection=com.atproto.lexicon.schema&rkey=com.example.authBasic"
             {:maximum 2097152 :timeout-ms 5000 :redirects 0}]] @calls))
    (reset! records [(str "did=" repository/did) (str "did=" repository/did) "unrelated=text"])
    (is (= schema (:schema (resolver/resolve! resolver nsid))))
    (is (= "_lexicon.blogging.lab.dept.university.edu." (resolver/authority-name "edu.university.dept.lab.blogging.getBlogPost")))
    (is (= "_lexicon.example.com." (resolver/authority-name "COM.Example.authBasic")))))

(deftest authority-failures-never-search-parents-or-fetch
  (let [{:keys [resolver records calls]} (fixture)]
    (doseq [bad [[] ["did=bad"] ["did=did:key:unsupported"]
                 [(str "did=" repository/did) "did=did:web:other.example.net"]
                 [(str "did=" repository/did) "did=bad"]]]
      (reset! records bad) (reset! calls [])
      (is (= :authority (error #(resolver/resolve! resolver nsid))))
      (is (= [[:dns "_lexicon.example.com."]] @calls)))
    (reset! calls [])
    (is (= :invalid-nsid (error #(resolver/resolve! resolver "com.example.auth#fragment"))))
    (is (empty? @calls))))

(deftest schema-identity-fetch-and-proof-failures-are-closed
  (let [{:keys [resolver key doc response]} (fixture) original @doc good @response]
    (doseq [bad [(assoc original "id" "did:web:wrong.example.net")
                 (dissoc original "service") (dissoc original "verificationMethod")
                 (document (crypto/keypair) "https://pds.example.net")]]
      (reset! doc bad)
      (is (some? (error #(resolver/resolve! resolver nsid)))))
    (reset! doc original)
    (doseq [bad [{:status 404 :body (byte-array 0)} {:status 302 :body (byte-array 0)}
                 {:status 200 :body (byte-array 2097153)} {:status 200 :body (codec/utf8 "{\"value\":{}}")}
                 {:status 200 :body (proofs/slice (repository/fixture key {}) path)}]]
      (reset! response bad)
      (is (some? (error #(resolver/resolve! resolver nsid)))))
    (doseq [bad [(assoc schema "$type" "com.example.record") (assoc schema "lexicon" 2)
                 (assoc schema "id" "com.example.other") (assoc schema "defs" {})
                 (assoc schema "defs" {"main" {}}) (assoc schema "defs" {"#main" {"type" "permission-set"}})]]
      (reset! response {:status 200 :body (proofs/slice (repository/fixture key {path bad}) path)})
      (is (= :schema (error #(resolver/resolve! resolver nsid)))))
    (reset! response good)
    (is (= schema (:schema (resolver/resolve! resolver nsid))) "Failures release the concurrency permit")))

(deftest resolution-concurrency-is-bounded
  (let [{:keys [resolver]} (fixture) permits (:permits resolver)]
    (.acquire ^java.util.concurrent.Semaphore permits 16)
    (try (is (= :busy (error #(resolver/resolve! resolver nsid))))
         (finally (.release ^java.util.concurrent.Semaphore permits 16)))
    (is (= schema (:schema (resolver/resolve! resolver nsid))))))

(deftest real-tls-record-proof-resolution-and-network-failures
  (let [store (net-test/test-keystore)
        manager (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm)) (.init store (.toCharArray "test-password")))
        ssl (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers manager) nil nil))
        server (HttpsServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        origin (str "https://good.example.com:" (.getPort (.getAddress server)))
        key (crypto/keypair) doc (document key origin)
        bytes (proofs/slice (repository/fixture key {path schema}) path)
        mode (atom :valid) hits (atom [])]
    (.setHttpsConfigurator server (HttpsConfigurator. ssl))
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [uri (.getRequestURI exchange) path (.getPath uri)
                  _ (swap! hits conj path)
                  did? (= path "/.well-known/did.json")
                  body (if did? (codec/utf8 (json/write-str doc))
                         (case @mode :large (byte-array 2097153) :invalid (codec/utf8 "not a car") bytes))]
              (when (and (not did?) (= :redirect @mode))
                (.set (.getResponseHeaders exchange) "Location" (str origin "/must-not-follow")))
              (when (and (not did?) (= :encoded @mode))
                (.set (.getResponseHeaders exchange) "Content-Encoding" "gzip"))
              (.sendResponseHeaders exchange (if (and (not did?) (= :redirect @mode)) 302 200) (alength body))
              (.write (.getResponseBody exchange) body))
            (catch java.io.IOException _) ; expected when the bounded client aborts
            (finally (.close exchange))))))
    (.start server)
    (try
      (with-open [client (net/open-client {:resolver (net-test/resolver "127.0.0.1") :address-policy (constantly true)
                                          :ssl-context (doto (SslContextFactory$Client.) (.setTrustStore store))})]
        (let [identity (identity/resolver {:fetch (fn [url opts]
                                                   (is (= "https://source.example.net/.well-known/did.json" url))
                                                   (net/fetch! client (str origin "/.well-known/did.json") opts))})
              resolver (resolver/resolver {:identity-resolver identity :http-client client
                                           :txt-lookup (constantly [(str "did=" repository/did)])})]
          (is (= schema (:schema (resolver/resolve! resolver nsid))))
          (doseq [behavior [:redirect :encoded :large :invalid]]
            (reset! mode behavior)
            (is (some? (error #(resolver/resolve! resolver nsid)))))
          (is (not-any? #{"/must-not-follow"} @hits))))
      (finally (.stop server 0)))))
