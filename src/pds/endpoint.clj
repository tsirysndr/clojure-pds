(ns pds.endpoint
  "Offline Lexicon validation for XRPC inputs. Parsing helpers invoke validation
  where handlers already read inputs, preserving authentication and transaction
  ordering. Binary uploads and subscription frames keep their bounded readers."
  (:require [clojure.string :as str]
            [pds.errors :as errors]
            [pds.lexicon :as lexicon]
            [pds.protocol.codec :as codec]))

(defn context [uri method]
  (when (and (str/starts-with? uri "/xrpc/")
             (or (str/starts-with? uri "/xrpc/com.atproto.") (contains? @lexicon/catalog (subs uri 6))))
    (let [id (subs uri 6) schema (get-in @lexicon/catalog [id "defs" "main"])
          expected ({"query" :get "procedure" :post "subscription" :get} (get schema "type"))]
      (when-not (and expected (= method expected))
        (throw (ex-info "Implemented XRPC route must match its pinned Lexicon" {:id id})))
      {:id id :schema schema})))

(defn array-keys [request]
  (into #{} (keep (fn [[key schema]] (when (= "array" (get schema "type")) key)))
        (get-in request [::context :schema "parameters" "properties"])))

(defn- validate! [id schema value]
  (try
    (lexicon/validate! @lexicon/catalog id schema value)
    (catch clojure.lang.ExceptionInfo e
      (if (= "InvalidRecord" (:error (ex-data e)))
        (errors/invalid! (.getMessage e))
        (throw e)))))

(defn- defaults [id schema value depth]
  (when (> depth 64) (errors/invalid! "Input exceeds the validation depth limit"))
  (let [child #(defaults id %1 %2 (inc depth))]
    (case (get schema "type")
      "ref" (let [[context target] (lexicon/definition @lexicon/catalog id (get schema "ref"))]
              (defaults context target value (inc depth)))
      "union" (if-let [ref (some #(when (= (lexicon/canonical-ref id %) (get value "$type")) %)
                                 (get schema "refs"))]
                (child {"type" "ref" "ref" ref} value) value)
      "array" (if (vector? value) (mapv #(child (get schema "items") %) value) value)
      "integer" (if (number? value)
                  (try (codec/from-json value) (catch Exception _ value)) value)
      "object" (if (map? value)
                 (reduce-kv (fn [result key property]
                              (cond
                                (contains? value key) (assoc result key (child property (get value key)))
                                (contains? property "default") (assoc result key (get property "default"))
                                :else result)) value (get schema "properties" {})) value)
      value)))

(defn json-input! [request value]
  ;; Keep the wire representation for existing handlers. Record writes convert
  ;; to native links/bytes separately, after account authorization checks.
  (let [{:keys [id schema]} (::context request)]
    (if-let [input (get-in schema ["input" "schema"])]
      (let [value (defaults id input value 0)
            native (try (codec/from-json value)
                        (catch Exception _ (errors/invalid! "Invalid AT Protocol input data")))]
        (validate! id input native)
        value)
      value)))

(defn- parameter [schema value]
  (case (get schema "type")
    "integer" (if (and (string? value) (re-matches #"-?[0-9]{1,19}" value))
                (try (Long/parseLong value) (catch NumberFormatException _ (errors/invalid! "Integer parameter is out of range")))
                (errors/invalid! "Expected an integer parameter"))
    "boolean" (case value "true" true "false" false (errors/invalid! "Expected a boolean parameter"))
    "array" (mapv #(parameter (get schema "items") %) value)
    "string" value
    (throw (ex-info "Unsupported Lexicon parameter type" {}))))

(defn query-input! [request params]
  (let [{:keys [id schema]} (::context request)]
    (if-let [parameters (get schema "parameters")]
      (let [params (reduce-kv (fn [result key property]
                               (if (and (not (contains? result key)) (contains? property "default"))
                                 (assoc result key (str (get property "default"))) result))
                             params (get parameters "properties"))
            native (reduce-kv (fn [result key schema]
                                (if (contains? params key)
                                  (assoc result key (parameter schema (get params key))) result))
                              params (get parameters "properties"))]
        (validate! id (assoc parameters "type" "object") native)
        params)
      params)))
