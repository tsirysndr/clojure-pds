(ns dagger.internal.runtime
  "Schema-aware lazy objects. Arguments travel as GraphQL variables."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dagger.connection :as connection]))

(def schema (delay (edn/read-string (slurp (io/resource "dagger/schema.edn")))))

(defrecord ObjectRef [client type-name selection])
(defmethod print-method ObjectRef [object ^java.io.Writer writer]
  (.write writer (str "#dagger/object{:type " (pr-str (:type-name object)) "}")))

(defn object? [value] (instance? ObjectRef value))
(defn client-of [value] (if (object? value) (:client value) value))
(defn root [client] (->ObjectRef client (:query-type @schema) []))

(defn kebab [s]
  (-> s (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace "_" "-") str/lower-case))

(defn named-type [ref] (if (:of-type ref) (recur (:of-type ref)) ref))
(defn nullable? [ref] (not= :non-null (:kind ref)))
(defn unwrap [ref] (if (nullable? ref) ref (:of-type ref)))
(defn graphql-type [{:keys [kind name of-type]}]
  (case kind :non-null (str (graphql-type of-type) "!")
        :list (str "[" (graphql-type of-type) "]") name))

(defn field [type-name field-name]
  (or (get-in @schema [:types type-name :fields field-name])
      (throw (ex-info "Unknown Dagger field; regenerate the SDK for your engine"
                      {:type :dagger/schema :object type-name :field field-name}))))

(defn- invalid! [message context]
  (throw (ex-info message (assoc context :type :dagger/invalid-argument))))

(declare execute! invoke-field)

(defn object-id [object]
  (invoke-field object (:type-name object) "id" {}))

(defn load-object [client type-name id]
  (let [root-type (:query-type @schema)
        loader (str "load" type-name "FromID")]
    (if (get-in @schema [:types root-type :fields loader])
      (->ObjectRef client type-name [{:name loader :arguments [["id" (get-in (field root-type loader) [:args "id" :type]) id]]}])
      (->ObjectRef client type-name [{:name "node" :arguments [["id" (get-in (field root-type "node") [:args "id" :type]) id]]}
                                     {:fragment type-name}]))))

(defn encode-value
  "Validate and encode a GraphQL input value. Objects resolve to IDs here."
  [client ref expected value]
  (cond
    (nil? value) (if (nullable? ref) nil (invalid! "A required argument cannot be nil" {:graphql-type (graphql-type ref)}))
    (= :non-null (:kind ref)) (encode-value client (:of-type ref) expected value)
    (= :list (:kind ref)) (if (sequential? value)
                           (mapv #(encode-value client (:of-type ref) expected %) value)
                           (invalid! "Expected a sequential collection" {:graphql-type (graphql-type ref)}))
    expected (if (and (object? value) (= expected (:type-name value)))
               (do (when-not (identical? client (:client value))
                     (invalid! "Object arguments must belong to the same Dagger connection" {:expected expected}))
                   (object-id value))
               (if (string? value) value (invalid! "Expected a Dagger object or ID string" {:expected expected})))
    (= :enum (:kind ref))
    (let [values (get-in @schema [:types (:name ref) :values])
          encoded (if (keyword? value)
                    (some #(when (= (kebab %) (name value)) %) values)
                    value)]
      (if (some #{encoded} values) encoded
          (invalid! "Unknown enum value" {:enum (:name ref) :value value :allowed values})))
    (= :input-object (:kind ref))
    (let [fields (get-in @schema [:types (:name ref) :input-fields])
          keys->fields (into {} (map (fn [[name f]] [(keyword (kebab name)) (assoc f :name name)]) fields))]
      (when-not (map? value) (invalid! "Expected an input map" {:input (:name ref)}))
      (doseq [k (keys value)] (when-not (contains? keys->fields k) (invalid! "Unknown input field" {:field k})))
      (into {} (keep (fn [[k f]]
                      (cond
                        (contains? value k) [(:name f) (encode-value client (:type f) (:expected f) (get value k))]
                        (and (not (nullable? (:type f))) (nil? (:default f))) (invalid! "Missing required input field" {:field k})
                        :else nil)) keys->fields)))
    (= "Boolean" (:name ref)) (if (boolean? value) value (invalid! "Expected a boolean" {}))
    (= "Int" (:name ref)) (if (and (integer? value) (<= Integer/MIN_VALUE value Integer/MAX_VALUE)) value (invalid! "Expected a GraphQL Int (32-bit integer)" {}))
    (= "Float" (:name ref)) (if (and (number? value) (Double/isFinite (double value))) (double value) (invalid! "Expected a finite number" {}))
    :else (if (string? value) value (invalid! "Expected a string scalar" {:scalar (:name ref)}))))

(defn document
  "Return {:query :variables} for a selection; object arguments may execute."
  ([object] (document object nil))
  ([object children]
   (let [variables (atom (sorted-map))
         definitions (atom [])
         fields (mapv
                 (fn [{:keys [name arguments fragment]}]
                   (if fragment (str "... on " fragment)
                     (str name
                          (when (seq arguments)
                            (str "(" (str/join "," (map
                                                     (fn [[name ref value expected]]
                                                       (let [key (str "v" (count @definitions))]
                                                         (swap! definitions conj (str "$" key ":" (graphql-type ref)))
                                                         (swap! variables assoc key (encode-value (:client object) ref expected value))
                                                         (str name ":$" key))) arguments)) ")")))))
                 (:selection object))]
     {:query (str "query" (when (seq @definitions) (str "(" (str/join "," @definitions) ")"))
                  "{" (str/join "{" fields) (when children (str "{" children "}"))
                  (apply str (repeat (count fields) "}")))
      :variables @variables})))

(defn execute!
  ([object] (execute! object nil))
  ([object children]
   (let [{:keys [query variables]} (document object children)
         data (connection/request! (:client object) query variables)
         path (keep :name (:selection object))]
     (reduce (fn [value key]
               (cond
                 (nil? value) (reduced nil)
                 (and (map? value) (contains? value key)) (get value key)
                 :else (throw (ex-info "Missing field in Dagger response" {:type :dagger/decode :field key})))) data path))))

(defn- decode-result [client ref expected value]
  (cond
    (nil? value) (if (nullable? ref) nil (throw (ex-info "Unexpected null from Dagger" {:type :dagger/decode :graphql-type (graphql-type ref)})))
    (= :non-null (:kind ref)) (decode-result client (:of-type ref) expected value)
    (= :list (:kind ref)) (if (vector? value) (mapv #(decode-result client (:of-type ref) expected %) value)
                            (throw (ex-info "Expected a list response" {:type :dagger/decode})))
    expected (if (string? value) (load-object client expected value)
                 (throw (ex-info "Expected an object ID" {:type :dagger/decode})))
    (#{:object :interface} (:kind ref)) (if (string? (get value "id")) (load-object client (:name ref) (get value "id"))
                                          (throw (ex-info "Expected an object with an ID" {:type :dagger/decode})))
    (= :enum (:kind ref)) (if (some #{value} (get-in @schema [:types (:name ref) :values]))
                           (keyword (kebab value))
                           (throw (ex-info "Unknown enum response" {:type :dagger/decode :enum (:name ref) :value value})))
    (= "Void" (:name ref)) nil
    :else (let [valid? (case (:name ref) "Boolean" (boolean? value) "Int" (integer? value)
                            "Float" (number? value) (string? value))]
            (if valid? value (throw (ex-info "Invalid scalar response" {:type :dagger/decode :scalar (:name ref)}))))))

(defn invoke-field
  "Invoke a schema field using keyword arguments. Object fields stay lazy."
  [self type-name field-name values]
  (let [object (if (object? self) self (root self))
        {:keys [args type expected]} (field type-name field-name)
        arg-map (into {} (map (fn [[n a]] [(keyword (kebab n)) (assoc a :name n)]) args))]
    (when-not (= type-name (:type-name object))
      (invalid! "Operation called on the wrong object type" {:expected type-name :actual (:type-name object)}))
    (when-not (map? values) (invalid! "Options must be a keyword map" {}))
    (doseq [k (keys values)] (when-not (contains? arg-map k) (invalid! "Unknown argument" {:field field-name :argument k})))
    (let [arguments (into [] (keep (fn [[k a]]
                                    (cond
                                      (contains? values k) [(:name a) (:type a) (get values k) (:expected a)]
                                      (and (not (nullable? (:type a))) (nil? (:default a)))
                                      (invalid! "Missing required argument" {:field field-name :argument k})
                                      :else nil)) (sort-by key arg-map)))
          result-type (named-type type)
          selection (conj (:selection object) {:name field-name :arguments arguments})
          result (->ObjectRef (:client object) (:name result-type) selection)]
      (if (and (#{:object :interface} (:kind result-type)) (not= :list (:kind (unwrap type))))
        result
        (decode-result (:client object) type expected
                       (execute! result (when (#{:object :interface} (:kind result-type)) "id")))))))
