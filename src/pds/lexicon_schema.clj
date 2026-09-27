(ns pds.lexicon-schema
  "Admit the reachable record-schema graph before using remote schemas as
  validator instructions. The lookup dependency must authenticate each document."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [pds.lexicon :as lexicon]
            [pds.protocol.codec :as codec]
            [pds.protocol.formats :as formats]
            [pds.protocol.syntax :as syntax]))

(defn- invalid! [] (throw (ex-info "Unsupported or invalid record schema" {:lexicon-error :schema})))
(defn- require! [value] (when-not value (invalid!)))
(defn- object? [value] (and (map? value) (not (record? value))))
(defn- signed-integer? [value] (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE)))
(defn- size? [value] (and (signed-integer? value) (<= 0 value)))
(defn- strings? [value] (and (vector? value) (every? string? value)))
(defn- mime? [value]
  (and (string? value)
       (or (= "*/*" value) (re-matches #"[A-Za-z0-9!#$&^_.+-]+/(?:[A-Za-z0-9!#$&^_.+-]+|\*)" value))))
(def fields
  {"boolean" #{"default" "const"}
   "integer" #{"default" "const" "minimum" "maximum" "enum"}
   "string" #{"default" "const" "enum" "knownValues" "format" "minLength" "maxLength" "minGraphemes" "maxGraphemes"}
   "bytes" #{"minLength" "maxLength"}
   "cid-link" #{} "blob" #{"accept" "maxSize"}
   "array" #{"items" "minLength" "maxLength"}
   "object" #{"properties" "required" "nullable"}
   "unknown" #{} "ref" #{"ref"} "union" #{"refs" "closed"}
   "record" #{"key" "record"}})
(def named-types (disj (set (keys fields)) "ref" "union" "unknown"))
(def field-types (disj (set (keys fields)) "record"))

(defn- optional! [schema key predicate]
  (when (contains? schema key) (require! (predicate (get schema key)))))
(defn- bound! [schema minimum maximum signed?]
  (doseq [key [minimum maximum]] (optional! schema key (if signed? signed-integer? size?)))
  (when (and (contains? schema minimum) (contains? schema maximum))
    (require! (<= (get schema minimum) (get schema maximum)))))
(defn- reference [context value]
  (require! (string? value))
  (let [absolute (lexicon/canonical-ref context value)
        [id name :as parts] (str/split absolute #"#" -1)]
    (require! (and (<= (count parts) 2) (syntax/nsid? id)
                   (or (= 1 (count parts)) (boolean (re-matches #"[A-Za-z][A-Za-z0-9]*" name)))))
    [id (or name "main")]))

(defn record-catalog!
  "Return a closed catalog of reachable definitions for collection. Each NSID is
  looked up at most once, including cycles. Reject unknown instructions, missing
  refs and non-record roots; never return a partially compiled catalog.
  Bounds: 32 documents, 4 MiB total / 1 MiB each, 10,000 schema nodes, depth 64.
  Perform lookup outside repository/account transactions. This function does not
  cache documents or turn resolution failure into successful record validation."
  [lookup collection]
  (require! (syntax/nsid? collection))
  (let [documents (atom {}) catalog (atom {}) visited (atom #{})
        total (atom 0) nodes (atom 0)]
    (letfn [(document! [id]
              (or (get @documents id)
                  (do
                    (require! (< (count @documents) 32))
                    (let [doc (lookup id)]
                      (require! (and (object? doc) (= 1 (get doc "lexicon")) (= id (get doc "id"))
                                     (object? (get doc "defs")) (seq (get doc "defs"))))
                      (let [size (try (alength (codec/encode doc)) (catch Exception _ (invalid!)))]
                        (require! (<= size 1000000))
                        (require! (<= (swap! total + size) (* 4 1024 1024))))
                      (swap! documents assoc id doc)
                      doc))))
            (named! [id name allowed depth]
              (require! (<= depth 64))
              (let [node (get-in (document! id) ["defs" name]) type (get node "type")]
                ;; Check the target type even when it was already visited via a
                ;; less restrictive ref. Unions must always target objects.
                (require! (and (allowed type) (named-types type)
                               (or (not= "record" type) (= "main" name))))
                (when-not (@visited [id name])
                  (swap! visited conj [id name])
                  (node! id node depth)
                  (swap! catalog assoc-in [id "defs" name] node)
                  (swap! catalog update id assoc "id" id "lexicon" 1))))
            (node! [id node depth]
              (require! (and (<= depth 64) (<= (swap! nodes inc) 10000) (object? node)))
              (let [type (get node "type") allowed (get fields type)
                    child! (fn [child]
                             (require! (field-types (get child "type")))
                             (node! id child (inc depth)))]
                (require! (and allowed (set/subset? (set (keys node)) (into #{"type" "description"} allowed))))
                (optional! node "description" string?)
                (case type
                  ("boolean" "integer" "string")
                  (let [predicate (case type "boolean" boolean? "integer" signed-integer? "string" string?)]
                    (doseq [key ["default" "const"]] (optional! node key predicate))
                    (require! (not (and (contains? node "const") (contains? node "default"))))
                    (optional! node "enum" #(and (vector? %) (every? predicate %)))
                    (when (= type "integer") (bound! node "minimum" "maximum" true))
                    (when (= type "string")
                      (bound! node "minLength" "maxLength" false)
                      (bound! node "minGraphemes" "maxGraphemes" false)
                      (optional! node "knownValues" strings?)
                      (optional! node "format" #(contains? formats/validators %))))
                  "bytes" (bound! node "minLength" "maxLength" false)
                  "blob" (do (optional! node "maxSize" size?)
                             (optional! node "accept" #(and (vector? %) (every? mime? %))))
                  "array" (do (bound! node "minLength" "maxLength" false) (child! (get node "items")))
                  "object" (let [properties (get node "properties")]
                             (require! (and (object? properties) (every? string? (keys properties))))
                             (doseq [key ["required" "nullable"]]
                               (optional! node key #(and (strings? %) (every? (partial contains? properties) %))))
                             (doseq [property (vals properties)] (child! property)))
                  "ref" (let [[target name] (reference id (get node "ref"))]
                          (named! target name named-types (inc depth)))
                  "union" (do (require! (strings? (get node "refs")))
                              (optional! node "closed" boolean?)
                              (require! (or (not (get node "closed")) (seq (get node "refs"))))
                              (doseq [ref (get node "refs") :let [[target name] (reference id ref)]]
                                (named! target name #{"object" "record"} (inc depth))))
                  "record" (let [key (get node "key")]
                             (require! (and (string? key)
                                            (or (#{"any" "tid"} key)
                                                (and (str/starts-with? key "literal:") (syntax/record-key? (subs key 8))))))
                             (require! (= "object" (get-in node ["record" "type"])))
                             (child! (get node "record")))
                  ("unknown" "cid-link") nil)))]
      (named! collection "main" #{"record"} 0)
      @catalog)))
