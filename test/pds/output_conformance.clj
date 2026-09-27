(ns pds.output-conformance
  "Test-only observer of actual PDS responses. Captures remain in this process
  and a deleted temporary file used by the pinned local reference validator."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pds.lexicon :as lexicon]
            [pds.response-body :as response-body]
            [pds.protocol.codec :as codec])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(def observations (atom []))

(defn observe-response! [id response]
  (when (= 200 (:status response))
    (let [schema (get-in @lexicon/catalog [id "defs" "main"])]
      (when schema
        (try
          (let [encoding (get-in schema ["output" "encoding"])
                type (some-> (get-in response [:headers "Content-Type"]) (str/split #";") first str/lower-case)
                body (:body response)]
            (swap! observations conj
                   {:id id :kind "output" :encoding encoding :content-type type
                    :value (when (= encoding "application/json") (json/read-str body))
                    :empty (or (nil? body) (= "" body) (and (bytes? body) (zero? (alength ^bytes body)))
                               (and (response-body/stream? body) (zero? (:length body))))
                    :binary (or (bytes? body) (response-body/stream? body))}))
          (catch Exception _ (swap! observations conj {:id id :kind "malformed-output"})))))))

(defn observe-frame! [id data]
  (try
    (let [[header payload] (codec/decode-pair data 5000000)]
      (swap! observations conj {:id id :kind "message" :header header :value (codec/to-json payload)}))
    (catch Exception _ (swap! observations conj {:id id :kind "malformed-message"}))))

(defn wrap [handler]
  (fn [request]
    (let [response (handler request) uri (:uri request)
          id (when (str/starts-with? uri "/xrpc/") (subs uri 6))]
      (when id (observe-response! id response))
      (if (and id (:websocket response))
        (update-in response [:websocket :on-open]
                   (fn [open!]
                     (fn [connection]
                       (open! (update connection :send!
                                      (fn [send!] (fn [data] (observe-frame! id data) (send! data))))))))
        response))))

(defn error-schema []
  {"type" "object" "required" ["error"]
   "properties" {"error" {"type" "string" "minLength" 1} "message" {"type" "string"}}})

(defn validate-observation! [{:keys [id kind header value encoding content-type empty binary]}]
  (let [main (get-in @lexicon/catalog [id "defs" "main"])]
    (case kind
      "output"
      (cond
        (nil? encoding) (when-not empty (throw (ex-info "Endpoint without output returned a body" {})))
        (= "application/json" encoding)
        (do (when-not (= content-type encoding) (throw (ex-info "Wrong JSON content type" {})))
            (lexicon/validate! @lexicon/catalog id (get-in main ["output" "schema"]) (codec/from-json value)))
        :else (when-not (and binary content-type (or (= "*/*" encoding) (= content-type encoding)))
                (throw (ex-info "Wrong binary output encoding" {}))))
      "message"
      (do
        (when (contains? value "$type") (throw (ex-info "Frame payload must omit $type" {})))
        (case (get header "op")
          1 (let [type (get header "t")]
              (when-not (some #{type} (get-in main ["message" "schema" "refs"]))
                (throw (ex-info "Unknown message type" {})))
              (lexicon/validate! @lexicon/catalog id (get-in main ["message" "schema"])
                                (assoc (codec/from-json value) "$type" (str id type))))
          -1 (do (when (contains? header "t") (throw (ex-info "Error header must omit t" {})))
                 (lexicon/validate! {} id (error-schema) value))
          (throw (ex-info "Invalid frame operation" {}))))
      (throw (ex-info "Malformed response or frame" {})))))

(deftest produced-responses-match-pinned-schemas
  (let [observed @observations]
    (doseq [item observed]
      (testing (str (:id item) " " (:kind item) " " (get-in item [:header "t"]))
        (is (= :valid (try (validate-observation! item) :valid
                          (catch Exception e (.getMessage e)))))))
    (let [expected (set (get (json/read-str (slurp (io/resource "lexicons/index.json"))) "endpointRoots"))
          seen (set (map :id observed))
          messages (set (keep #(get-in % [:header "t"]) observed))]
      (is (= expected seen) (str "Missing successful endpoint coverage: " (sort (set/difference expected seen))))
      (is (= #{"#account" "#commit" "#identity" "#info" "#sync"} messages))
      (is (some #(= -1 (get-in % [:header "op"])) observed) "Error frames are exercised too")
      (println "Validated" (count observed) "responses/frames across" (count seen) "endpoints and" (count messages) "message types"))
    (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
      (let [path (Files/createTempFile "pds-responses-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (spit (str path) (json/write-str observed))
          (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/verify-outputs.mjs" (str path)]) (.redirectErrorStream true)))
                done? (.waitFor process 60 TimeUnit/SECONDS)]
            (when-not done? (.destroyForcibly process))
            (is done?)
            (when done? (is (= 0 (.exitValue process)) (slurp (.getInputStream process)))))
          (finally (Files/deleteIfExists path)))))))
