(ns pds.lexicon
  "Validation against a trusted, offline Lexicon catalog. Values use native
  data-model types; callers must first validate/convert JSON with codec/from-json."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pds.errors :as errors]
            [pds.protocol.codec :as codec]
            [pds.protocol.formats :as formats]
            [pds.protocol.syntax :as syntax])
  (:import [java.util HexFormat]))

(defn load-catalog []
  (let [index (json/read-str (slurp (io/resource "lexicons/index.json")))]
    (into {}
          (for [[id hash] (get index "schemas")
                :let [data (with-open [in (io/input-stream (io/resource (str "lexicons/" id ".json")))]
                             (.readAllBytes in))
                      schema (json/read-str (codec/text data))]]
            (do
              (when-not (and (= hash (.formatHex (HexFormat/of) (codec/sha256 data)))
                             (= id (get schema "id")) (= 1 (get schema "lexicon")))
                (throw (ex-info "Lexicon catalog integrity check failed" {:schema id})))
              [id schema])))))

(def catalog (delay (load-catalog)))

(defn canonical-ref [context ref]
  (let [absolute (if (str/starts-with? ref "#") (str context ref) ref)]
    (str/replace absolute #"#main$" "")))

(defn definition [catalog context ref]
  (let [absolute (canonical-ref context ref)
        [id name] (str/split absolute #"#" 2)]
    [id (get-in catalog [id "defs" (or name "main")])]))

(defn- invalid! [path reason]
  (errors/raise! 400 "InvalidRecord" (str path " " reason)))

(defn- require! [valid path reason]
  (when-not valid (invalid! path reason)))

(defn- bounds! [schema value minimum maximum path]
  (when-let [lower (get schema minimum)] (require! (<= lower value) path (str "is below " minimum)))
  (when-let [upper (get schema maximum)] (require! (<= value upper) path (str "exceeds " maximum))))

(defn- plain-object? [value]
  (and (map? value) (not (record? value)) (not= "blob" (get value "$type"))))

(declare validate!)

(defn validate!
  "Validate a native data-model value; return it unchanged. Schemas are trusted
  program data, never client-supplied. Depth bounds also stop recursive refs."
  ([catalog context schema value] (validate! catalog context schema value "$" 0))
  ([catalog context schema value path depth]
   (require! (<= depth 64) path "exceeds the validation depth limit")
   (let [child! (fn [schema value path] (validate! catalog context schema value path (inc depth)))]
     (case (get schema "type")
       "boolean" (require! (boolean? value) path "must be a boolean")
       "integer" (do (require! (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE)) path "must be an integer")
                     (bounds! schema value "minimum" "maximum" path))
       "string" (do (require! (string? value) path "must be a string")
                    (bounds! schema (alength (codec/utf8 value)) "minLength" "maxLength" path)
                    (when (or (contains? schema "minGraphemes") (contains? schema "maxGraphemes"))
                      (bounds! schema (count (re-seq #"\X" value)) "minGraphemes" "maxGraphemes" path))
                    (when-let [format (get schema "format")]
                      (if-let [valid? (formats/validators format)]
                        (require! (valid? value) path (str "must have format " format))
                        (throw (ex-info "Unsupported Lexicon string format" {:format format})))))
       "bytes" (do (require! (bytes? value) path "must be bytes")
                   (bounds! schema (alength ^bytes value) "minLength" "maxLength" path))
       "cid-link" (require! (instance? pds.protocol.codec.Link value) path "must be a CID link")
       "blob" (do (require! (and (map? value) (= "blob" (get value "$type"))) path "must be a blob")
                  (bounds! schema (get value "size") "minSize" "maxSize" path)
                  (when-let [accept (get schema "accept")]
                    (require! (some #(or (= % "*/*") (= % (get value "mimeType"))
                                         (and (str/ends-with? % "/*")
                                              (= (subs % 0 (- (count %) 2))
                                                 (first (str/split (get value "mimeType") #"/"))))) accept)
                              path "has an unsupported blob MIME type")))
       "array" (do (require! (vector? value) path "must be an array")
                   (bounds! schema (count value) "minLength" "maxLength" path)
                   (doseq [[i item] (map-indexed vector value)]
                     (child! (get schema "items") item (str path "[" i "]"))))
       "object" (do (require! (plain-object? value) path "must be an object")
                    (doseq [key (get schema "required")]
                      (require! (contains? value key) (str path "." key) "is required"))
                    (doseq [[key property] (get schema "properties") :when (contains? value key)]
                      (when-not (and (nil? (get value key)) (some #{key} (get schema "nullable")))
                        (child! property (get value key) (str path "." key)))))
       "unknown" (require! (plain-object? value) path "must be an object")
       "ref" (let [[id target] (definition catalog context (get schema "ref"))]
               (when-not target (throw (ex-info "Unresolved Lexicon reference" {:ref (get schema "ref")})))
               (when (= "record" (get target "type"))
                 (require! (= id (get value "$type")) (str path ".$type") "must match the referenced record"))
               (validate! catalog id target value path (inc depth)))
       "union" (let [tag (get value "$type")
                     refs (set (map #(canonical-ref context %) (get schema "refs")))]
                 (require! (and (plain-object? value) (syntax/type-ref? tag)) path "must have a union $type")
                 (if (refs tag)
                   (child! {"type" "ref" "ref" tag} value path)
                   (require! (not (get schema "closed" false)) path "has an unknown union $type")))
       "record" (child! (get schema "record") value path)
       (throw (ex-info "Unsupported Lexicon value type" {:type (get schema "type")})))
     (when (contains? schema "const") (require! (= value (get schema "const")) path "does not match const"))
     (when (contains? schema "enum") (require! (some #{value} (get schema "enum")) path "is not in enum"))
     value)))

(defn validate-record!
  "Return valid/unknown, or nil when validation is explicitly skipped. Generic
  data-model, collection/$type and record-key syntax checks always apply outside
  this function. An unknown schema cannot satisfy validate=true."
  ([collection rkey value mode] (validate-record! @catalog collection rkey value mode))
  ([catalog collection rkey value mode]
   (when-not (false? mode)
     (if-let [schema (get-in catalog [collection "defs" "main"])]
       (do
         (require! (= "record" (get schema "type")) "$" "schema is not a record")
         (require! (= collection (get value "$type")) "$.$type" "must match the collection")
         (let [key (get schema "key")]
           (require! (cond (= key "any") true
                           (= key "tid") (syntax/tid? rkey)
                           (str/starts-with? key "literal:") (= rkey (subs key 8))
                           :else false)
                     "$rkey" "does not match the record schema"))
         (validate! catalog collection schema value)
         "valid")
       (if (true? mode)
         (invalid! "$" "has no known record schema")
         "unknown")))))
