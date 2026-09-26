(ns pds.request
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.errors :as errors]
            [pds.protocol.codec :as codec])
  (:import [java.io InputStream]
           [java.net URLDecoder]))

(defn body-bytes [request maximum]
  (if-let [^InputStream stream (:body request)]
    (let [bytes (.readNBytes stream (inc maximum))]
      (when (> (alength bytes) maximum) (errors/raise! 413 "PayloadTooLarge" "Request body exceeds the limit")) bytes)
    (byte-array 0)))
(defn- bounded-json! [text]
  ;; Scan nesting before calling the recursive JSON reader. Quoted brackets do
  ;; not affect depth, and escaped quotes do not end a string.
  (loop [chars (seq text) depth 0 quoted? false escaped? false]
    (when-let [ch (first chars)]
      (cond
        escaped? (recur (next chars) depth quoted? false)
        (and quoted? (= ch \")) (recur (next chars) depth false false)
        (and quoted? (= ch \\)) (recur (next chars) depth true true)
        quoted? (recur (next chars) depth true false)
        (= ch \" ) (recur (next chars) depth true false)
        (#{\{ \[} ch) (do (when (>= depth 64) (errors/invalid! "JSON nesting exceeds 64 levels"))
                               (recur (next chars) (inc depth) false false))
        (#{\} \]} ch) (recur (next chars) (dec depth) false false)
        :else (recur (next chars) depth false false)))))

(defn json-body [request]
  (when-not (= "application/json" (some-> (get-in request [:headers "content-type"]) (str/split #";") first str/lower-case))
    (errors/raise! 415 "InvalidRequest" "Expected application/json"))
  (let [data (body-bytes request (* 1024 1024))]
    (try
      (let [text (codec/text data)
            _ (bounded-json! text)
            value (json/read-str text)]
        (when-not (map? value) (errors/invalid! "Expected a JSON object")) value)
      (catch Exception _ (errors/invalid! "Invalid JSON object")))))
(defn query-params
  ([request] (query-params request #{}))
  ([request array-keys]
  (try
    (reduce (fn [result pair]
              (let [[k v] (str/split pair #"=" 2)
                    k (URLDecoder/decode k "UTF-8") v (URLDecoder/decode (or v "") "UTF-8")]
                (if (contains? array-keys k)
                  (update result k (fnil conj []) v)
                  (do (when (contains? result k) (errors/invalid! "Duplicate query parameter"))
                      (assoc result k v)))))
            {} (if (str/blank? (:query-string request)) [] (str/split (:query-string request) #"&")))
    (catch Exception _ (errors/invalid! "Invalid query parameters")))))
(defn string! [value name]
  (when-not (and (string? value) (not (str/blank? value))) (errors/invalid! (str name " is required"))) value)
(defn limit! [params default maximum]
  (let [n (get params "limit" (str default))]
    (when-not (and (string? n) (re-matches #"[0-9]{1,5}" n)) (errors/invalid! "Invalid limit"))
    (let [n (Long/parseLong n)] (when-not (<= 1 n maximum) (errors/invalid! "Invalid limit")) n)))
