(ns dagger.modules
  "Data-driven Dagger module definitions, schema registration, and invocation.
  A module is a map with :name and :functions. Handlers receive context and an
  argument map; they return ordinary values or lazy Dagger objects."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [dagger.connection :as connection]
            [dagger.internal.runtime :as runtime]))

(def primitives {:string "STRING_KIND" :int "INTEGER_KIND" :float "FLOAT_KIND"
                 :boolean "BOOLEAN_KIND" :void "VOID_KIND"})
(defn- failure [message data] (throw (ex-info message (assoc data :type :dagger/module))))
(defn- key-name [key] (str/replace (name key) "-" "_"))
(defn- arg-definition [arg] (if (map? arg) arg {:type arg}))
(defn- call [object field args] (runtime/invoke-field object (:type-name object) field args))
(defn- root-call [client field args] (runtime/invoke-field client (:query-type @runtime/schema) field args))
(defn- objects [module] (cons (dissoc module :objects) (:objects module)))

(defn validate!
  "Validate a definition before contacting the engine. Returns the definition."
  [module]
  (let [all (objects module)
        names (map :name all)]
    (when-not (and (every? #(and (string? %) (re-matches #"[A-Z][A-Za-z0-9]*" %)) names)
                   (= (count names) (count (set names))))
      (failure "Object names must be unique PascalCase identifiers" {:names names}))
    (letfn [(type! [type]
              (when-not (or (contains? primitives type)
                            (and (vector? type) (= 2 (count type))
                                 (case (first type)
                                   (:optional :list) (do (type! (second type)) true)
                                   :object (or (some #{(second type)} names)
                                               (#{:object :interface} (get-in @runtime/schema [:types (second type) :kind])))
                                   :enum (or (get (:enums module) (second type))
                                             (= :enum (get-in @runtime/schema [:types (second type) :kind])))
                                   :scalar (= :scalar (get-in @runtime/schema [:types (second type) :kind]))
                                   false)))
                (failure "Unsupported module type" {:value type})))
            (function! [f]
              (when-not (ifn? (:fn f)) (failure "Function requires a :fn handler" {}))
              (type! (:returns f))
              (doseq [[name arg] (:args f)]
                (when-not (keyword? name) (failure "Argument names must be keywords" {:argument name}))
                (type! (:type (arg-definition arg)))))]
      (doseq [object all]
        (doseq [[name type] (:fields object)] (when-not (keyword? name) (failure "Field names must be keywords" {})) (type! type))
        (doseq [[name f] (:functions object)]
          (when-not (keyword? name) (failure "Function names must be keywords" {:function name}))
          (when (contains? (:fields object) name) (failure "A field and function share a name" {:name name}))
          (function! f))
        (when-let [constructor (:constructor object)]
          (function! (assoc constructor :returns [:object (:name object)]))))))
  module)

(defn- enum-values [module type-name]
  (or (get (:enums module) type-name) (get-in @runtime/schema [:types type-name :values])))

(declare decode-value encode-value)
(defn- value-error [type value] (failure "Value does not match the declared module type" {:expected type :value value}))
(defn- scalar-value [type value]
  (if (case type
        :string (string? value)
        :int (and (integer? value) (<= Integer/MIN_VALUE value Integer/MAX_VALUE))
        :float (and (number? value) (Double/isFinite (double value)))
        :boolean (boolean? value)
        :void (nil? value)
        false)
    value (value-error type value)))

(defn decode-value
  "Decode a module JSON value to Clojure, including engine objects from IDs."
  [module client type value]
  (if (keyword? type) (scalar-value type value)
    (let [[kind inner] type]
      (case kind
        :optional (when-not (nil? value) (decode-value module client inner value))
        :list (if (sequential? value) (mapv #(decode-value module client inner %) value) (value-error type value))
        :scalar (if (string? value) value (value-error type value))
        :enum (if (some #{value} (enum-values module inner)) (keyword (runtime/kebab value)) (value-error type value))
        :object (if-let [object (some #(when (= inner (:name %)) %) (objects module))]
                  (if (map? value)
                    (reduce-kv (fn [state key field-type]
                                 (assoc state key (decode-value module client field-type
                                                               (get value (key-name key)))))
                               (into {} (map (fn [[k v]] [(keyword (runtime/kebab (name k))) v])) value)
                               (or (:fields object) {}))
                    (value-error type value))
                  (if (string? value) (runtime/load-object client inner value) (value-error type value)))
        (value-error type value)))))

(defn encode-value
  "Encode a handler's return value. Engine objects are evaluated to IDs."
  [module type value]
  (if (keyword? type) (scalar-value type value)
    (let [[kind inner] type]
      (case kind
        :optional (when-not (nil? value) (encode-value module inner value))
        :list (if (sequential? value) (mapv #(encode-value module inner %) value) (value-error type value))
        :scalar (if (string? value) value (value-error type value))
        :enum (let [values (enum-values module inner)
                    v (if (keyword? value) (some #(when (= (name value) (runtime/kebab %)) %) values) value)]
                (if (some #{v} values) v (value-error type value)))
        :object (if-let [object (some #(when (= inner (:name %)) %) (objects module))]
                  (if (map? value)
                    (reduce-kv (fn [state key field-type]
                                 (assoc state (key-name key) (encode-value module field-type (get value key))))
                               (into {} (map (fn [[k v]] [(key-name k) v])) value)
                               (or (:fields object) {}))
                    (value-error type value))
                  (if (and (runtime/object? value) (= inner (:type-name value)))
                    (runtime/object-id value) (value-error type value)))
        (value-error type value)))))

(defn invoke!
  "Invoke a module handler without the engine protocol. `arguments` and `parent`
  are decoded JSON values (string keys). Useful for fast module unit tests."
  [module client object-name function-name parent arguments]
  (validate! module)
  (let [object (or (some #(when (= object-name (:name %)) %) (objects module))
                   (failure "Unknown module object" {:object object-name}))
        key (keyword (runtime/kebab function-name))
        ctor? (empty? function-name)
        f (if ctor? (some-> (:constructor object) (assoc :returns [:object object-name]))
              (get-in object [:functions key]))
        parent (if ctor? {} (decode-value module client [:object object-name] (or parent {})))]
    (cond
      (and ctor? (nil? f)) (encode-value module [:object object-name] (or (:initial-state object) {}))
      (and (nil? f) (contains? (:fields object) key)) (encode-value module (get-in object [:fields key]) (get parent key))
      (nil? f) (failure "Unknown module function" {:object object-name :function function-name})
      :else
      (let [provided (into {} (map (fn [[k v]] [(keyword (runtime/kebab (name k))) v])) arguments)
            _ (doseq [key (keys provided)] (when-not (contains? (:args f) key) (failure "Unknown argument" {:argument key})))
            args (into {}
                       (for [[name raw] (:args f) :let [arg (arg-definition raw) type (:type arg)]]
                         [name (cond
                                 (contains? provided name) (decode-value module client type (get provided name))
                                 (contains? arg :default) (decode-value module client type (:default arg))
                                 (and (vector? type) (= :optional (first type))) nil
                                 :else (failure "Missing required argument" {:argument name}))]))]
        (encode-value module (:returns f) ((:fn f) {:client client :parent parent} args))))))

(defn- type-definition [client type]
  (let [base (root-call client "typeDef" {})]
    (if (keyword? type) (call base "withKind" {:kind (get primitives type)})
      (let [[kind inner] type]
        (case kind
          :optional (call (type-definition client inner) "withOptional" {:optional true})
          :list (call base "withListOf" {:element-type (type-definition client inner)})
          :object (call base "withObject" {:name inner})
          :enum (call base "withEnum" {:name inner})
          :scalar (call base "withScalar" {:name inner}))))))

(defn- function-definition [client name function]
  (reduce
    (fn [definition [name raw]]
      (let [arg (arg-definition raw)]
        (call definition "withArg"
              (cond-> {:name (key-name name) :type-def (type-definition client (:type arg))}
                (:description arg) (assoc :description (:description arg))
                (contains? arg :default) (assoc :default-value (json/write-str (:default arg)))
                (:default-path arg) (assoc :default-path (:default-path arg))
                (:ignore arg) (assoc :ignore (:ignore arg))))))
    (cond-> (root-call client "function" {:name name :return-type (type-definition client (:returns function))})
      (:description function) (call "withDescription" {:description (:description function)}))
    (sort-by key (:args function))))

(defn register!
  "Register the module definition and return its engine Module ID."
  [client module]
  (validate! module)
  (let [definition
        (reduce
          (fn [definition object]
            (let [type (root-call client "typeDef" {})
                  type (call type "withObject" (cond-> {:name (:name object)} (:description object) (assoc :description (:description object))))
                  type (reduce (fn [t [name field-type]] (call t "withField" {:name (key-name name) :type-def (type-definition client field-type)})) type (sort-by key (:fields object)))
                  type (reduce (fn [t [name f]] (call t "withFunction" {:function (function-definition client (key-name name) f)})) type (sort-by key (:functions object)))
                  type (if-let [ctor (:constructor object)]
                         (call type "withConstructor" {:function (function-definition client "" (assoc ctor :returns [:object (:name object)]))}) type)]
              (call definition "withObject" {:object type})))
          (reduce (fn [definition [name values]]
                    (let [enum (reduce #(call %1 "withEnumValue" {:value %2})
                                       (call (root-call client "typeDef" {}) "withEnum" {:name name}) values)]
                      (call definition "withEnum" {:enum enum})))
                  (root-call client "module" {}) (sort-by key (:enums module)))
          (objects module))]
    (runtime/object-id definition)))

(defn serve-with-client!
  "Serve one currentFunctionCall using an existing client. Returns nil."
  [client module]
  (validate! module)
  (let [invocation (get (connection/request! client "query{currentFunctionCall{parentName name parent inputArgs{name value}}}") "currentFunctionCall")
        {:strs [parentName name parent inputArgs]} invocation
        _ (when-not (and (string? parentName) (string? name) (vector? inputArgs)) (failure "Malformed current function call" {}))
        value (if (empty? parentName) (register! client module)
                  (invoke! module client parentName name
                           (when-not (str/blank? parent) (json/read-str parent))
                           (into {} (map #(vector (get % "name") (json/read-str (get % "value")))) inputArgs)))]
    (call (root-call client "currentFunctionCall" {}) "returnValue" {:value (json/write-str value)})
    nil))

(defn serve!
  "Serve a Dagger module invocation. Call this from your -main function."
  [module]
  (with-open [client (connection/connect)] (serve-with-client! client module)))

(defn- hinted-type [metadata]
  (or (:dagger/type metadata)
      (let [tag (:tag metadata) tag (if (class? tag) (.getSimpleName ^Class tag) (str tag))]
        (case tag ("String" "java.lang.String") :string
              ("long" "Long" "int" "Integer") :int
              ("double" "Double" "float" "Float") :float
              ("boolean" "Boolean") :boolean nil))))

(defn from-ns
  "Expose ordinary public defns marked with :dagger/return metadata.

  (defn ^{:dagger/return :string} hello [^String name] (str \"Hello \" name))
  (modules/serve! (modules/from-ns 'my-module \"MyModule\"))

  Parameter types come from JVM hints or :dagger/type metadata. Metadata may
  also specify :dagger/default, :dagger/description, :dagger/default-path,
  and :dagger/ignore. A ^:dagger/context parameter receives {:client :parent}
  and is not exposed as an engine argument. Multi-arity and variadic exports
  are rejected; ordinary private helpers need no metadata."
  [namespace object-name]
  (let [functions
        (into (sorted-map)
              (for [[symbol var] (ns-publics namespace)
                    :let [metadata (meta var)]
                    :when (or (contains? metadata :dagger/return) (:dagger/function metadata))]
                (let [arglists (:arglists metadata)
                      params (first arglists)]
                  (when-not (and (= 1 (count arglists)) (not-any? #{'&} params))
                    (failure "Exported functions must have one fixed arity" {:function symbol}))
                  (let [arguments
                        (into (array-map)
                              (for [parameter params :let [m (meta parameter)] :when (not (:dagger/context m))]
                                (do
                                  (when-not (symbol? parameter)
                                    (failure "Exported arguments must be symbols; destructure inside the function" {:function symbol}))
                                  (let [type (or (hinted-type m) (failure "Add a type hint or :dagger/type to the argument" {:function symbol :argument parameter}))]
                                    [(keyword (runtime/kebab (name parameter)))
                                     (cond-> {:type type}
                                       (contains? m :dagger/default) (assoc :default (:dagger/default m))
                                       (:dagger/description m) (assoc :description (:dagger/description m))
                                       (:dagger/default-path m) (assoc :default-path (:dagger/default-path m))
                                       (:dagger/ignore m) (assoc :ignore (:dagger/ignore m)))]))))]
                    [(keyword (runtime/kebab (name symbol)))
                     {:args arguments
                      :returns (or (:dagger/return metadata) (hinted-type metadata)
                                   (failure "Exported function needs a return type" {:function symbol}))
                      :description (:doc metadata)
                      :fn (fn [context args]
                            (apply var (map #(if (:dagger/context (meta %)) context
                                               (get args (keyword (runtime/kebab (name %))))) params)))}]))))]
    (validate! {:name object-name :functions functions})))
