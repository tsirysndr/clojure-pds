(ns pds.endpoint-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pds.endpoint :as endpoint]
            [pds.lexicon :as lexicon]
            [pds.protocol.codec :as codec]
            [pds.request :as request]
            [pds.xrpc :as xrpc])
  (:import [java.io ByteArrayInputStream]
           [java.net URLEncoder]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(def index (json/read-str (slurp (io/resource "lexicons/index.json"))))
(defn sample [id schema]
  (case (get schema "type")
    "ref" (let [[context target] (lexicon/definition @lexicon/catalog id (get schema "ref"))] (sample context target))
    "union" (let [ref (first (get schema "refs"))]
              (assoc (sample id {"type" "ref" "ref" ref}) "$type" (lexicon/canonical-ref id ref)))
    "array" [(sample id (get schema "items"))]
    "unknown" {}
    "object" (into {} (for [[key property] (get schema "properties")] [key (sample id property)]))
    "boolean" true
    "integer" (get schema "minimum" 1)
    "string" (case (get schema "format")
               ("did" "at-identifier") "did:web:alice.example.com"
               "handle" "alice.example.com"
               "nsid" "com.example.record"
               "record-key" "one"
               "at-uri" "at://did:web:alice.example.com/com.example.record/one"
               "cid" (codec/cid (byte-array [1]))
               "tid" "2222222222222"
               "datetime" "2026-09-26T00:00:00Z"
               "value")
    (throw (ex-info "Unhandled fixture schema" {:schema schema}))))

(def fixtures
  (vec (mapcat
    (fn [id]
      (let [main (get-in @lexicon/catalog [id "defs" "main"])]
        (mapcat
          (fn [[kind schema]]
            (when schema
              (let [schema (assoc schema "type" "object") value (sample id schema)
                    fixture {:id id :kind kind :value value :valid true}]
                (concat [fixture]
                        (for [key (get schema "required")]
                          (assoc fixture :value (dissoc value key)
                                 :valid (contains? (get-in schema ["properties" key]) "default")))
                        (when (= kind "input")
                          (for [key (keys (get schema "properties"))]
                            (assoc fixture :value (assoc value key nil)
                                   :valid (boolean (some #{key} (get schema "nullable"))))))))))
          [["input" (get-in main ["input" "schema"])] ["params" (get main "parameters")]])))
    (get index "endpointRoots"))))

(defn query-string [params]
  (str/join "&" (for [[key value] params item (if (vector? value) value [value])]
                  (str (URLEncoder/encode key "UTF-8") "=" (URLEncoder/encode (str item) "UTF-8")))))

(defn invoke [id kind value]
  (let [method (if (= kind "input") :post :get) uri (str "/xrpc/" id)
        handler (xrpc/router {uri {:method method :handler #(xrpc/response 200 ((if (= kind "input") request/json-body request/query-params) %))}})]
    (handler (cond-> {:uri uri :request-method method :headers {"content-type" "application/json"}}
               (= kind "input") (assoc :body (ByteArrayInputStream. (codec/utf8 (json/write-str value))))
               (= kind "params") (assoc :query-string (query-string value))))))

(deftest catalog-input-contracts
  (is (= 63 (count (get index "endpointRoots")))))

(deftest required-optional-nullable-fields-agree-with-pinned-schemas
  (doseq [{:keys [id kind value valid]} fixtures]
    (testing (str id " " kind " " (pr-str value))
      (let [response (invoke id kind value)]
        (is (= (if valid 200 400) (:status response)))
        (when-not valid (is (= "InvalidRequest" (get (json/read-str (:body response)) "error"))))))))

(deftest query-types-repetition-defaults-and-body-defaults
  (let [id "com.atproto.repo.listRecords" params {"repo" "alice.example.com" "collection" "com.example.record"}]
    (doseq [[key value] [["limit" "1.5"] ["limit" "1e2"] ["limit" "9223372036854775808"]
                        ["limit" "0"] ["limit" "101"] ["reverse" "1"] ["reverse" "TRUE"]
                        ["repo" "bad"] ["collection" "bad"]]]
      (is (= 400 (:status (invoke id "params" (assoc params key value))))))
    (is (= "50" (get (json/read-str (:body (invoke id "params" params))) "limit")))
    (is (= 400 (:status (invoke id "params" (assoc params "repo" ["alice.example.com" "bob.example.com"]))))))
  (let [id "com.atproto.sync.getBlocks" cid (codec/cid (byte-array [1]))
        result (invoke id "params" {"did" "did:web:alice.example.com" "cids" [cid cid]})]
    (is (= 200 (:status result)))
    (is (= [cid cid] (get (json/read-str (:body result)) "cids"))))
  (is (= 1 (get (json/read-str (:body (invoke "com.atproto.server.createInviteCodes" "input" {"useCount" 2}))) "codeCount")))
  (is (= {"codeCount" 1 "useCount" 2}
         (json/read-str (:body (invoke "com.atproto.server.createInviteCodes" "input" {"useCount" 2.0})))))
  (let [record {"$type" "com.example.record" "arbitrary" {"x" 1}}
        value {"repo" "alice.example.com" "collection" "com.example.record" "record" record "futureField" false}]
    (is (= value (json/read-str (:body (invoke "com.atproto.repo.createRecord" "input" value))))))
  (is (thrown? Exception (xrpc/router {"/xrpc/com.atproto.repo.createRecord" {:method :get :handler identity}})))
  (is (thrown? Exception (xrpc/router {"/xrpc/com.atproto.example.missing" {:method :get :handler identity}}))))

(deftest pinned-upstream-validates-the-same-input-cases
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [path (Files/createTempFile "pds-endpoint-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (str path) (json/write-str fixtures))
        (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/verify-endpoints.mjs" (str path)]) (.redirectErrorStream true)))
              done? (.waitFor process 30 TimeUnit/SECONDS)]
          (when-not done? (.destroyForcibly process))
          (is done?)
          (when done? (is (= 0 (.exitValue process)) (slurp (.getInputStream process)))))
        (finally (Files/deleteIfExists path))))))
