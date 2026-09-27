(ns pds.lexicon-schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [pds.lexicon :as lexicon]
            [pds.lexicon-schema :as schema]
            [pds.lexicon-test :as fixtures]))

(def id "com.example.note")
(defn record-doc [properties]
  {"lexicon" 1 "id" id "defs" {"main" {"type" "record" "key" "any"
                                      "record" {"type" "object" "properties" properties}}}})
(defn compile-property [property]
  (schema/record-catalog! {id (record-doc {"value" property})} id))
(defn rejects? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= :schema (:lexicon-error (ex-data e))))))

(deftest bundled-record-graphs-and-upstream-fixture
  (let [ids (for [[id doc] @lexicon/catalog :when (= "record" (get-in doc ["defs" "main" "type"]))] id)]
    (is (= 17 (count ids)))
    (doseq [id ids]
      (let [catalog (schema/record-catalog! @lexicon/catalog id)]
        (is (= (get-in @lexicon/catalog [id "defs" "main"]) (get-in catalog [id "defs" "main"])) id))))
  (let [catalog (schema/record-catalog! fixtures/demo-catalog fixtures/demo)]
    (is (= "valid" (lexicon/validate-record! catalog fixtures/demo "demo"
                                            {"$type" fixtures/demo "integer" 1} true)))))

(deftest schema-instructions-are-validated-before-record-values
  (doseq [property [{"type" "boolean" "const" false}
                    {"type" "integer" "minimum" -5 "maximum" 0 "enum" [-5 0]}
                    {"type" "string" "maxLength" 5 "maxGraphemes" 2 "knownValues" ["one"]}
                    {"type" "bytes" "minLength" 0}
                    {"type" "cid-link"} {"type" "unknown"}
                    {"type" "blob" "accept" ["image/*" "*/*"] "maxSize" 100}
                    {"type" "array" "items" {"type" "string"} "maxLength" 5}
                    {"type" "union" "refs" []}]]
    (is (map? (compile-property property))))
  (doseq [property [{"type" "string" "format" "future"}
                    {"type" "string" "pattern" "ignored?"}
                    {"type" "boolean" "const" "false"}
                    {"type" "boolean" "const" false "default" false}
                    {"type" "integer" "maximum" "10"}
                    {"type" "integer" "minimum" 10 "maximum" 1}
                    {"type" "integer" "enum" [1 "two"]}
                    {"type" "string" "maxLength" -1}
                    {"type" "string" "maxLength" 1.5}
                    {"type" "string" "knownValues" "bad"}
                    {"type" "bytes" "minLength" nil}
                    {"type" "blob" "accept" ["image/p*"]}
                    {"type" "array" "items" nil}
                    {"type" "union" "refs" [] "closed" true}
                    {"type" "union" "refs" [] "closed" "false"}
                    {"type" "object" "properties" {} "required" ["missing"]}
                    {"type" "object" "properties" {} "nullable" ["missing"]}
                    {"type" "token"} {"type" "record"} {"type" "future"}]]
    (is (rejects? #(compile-property property)) (pr-str property))))

(deftest references-are-closed-and-type-checked
  (let [external "net.publisher.defs"
        docs {id (record-doc {"value" {"type" "ref" "ref" (str external "#entry")}})
              external {"lexicon" 1 "id" external "defs"
                        {"entry" {"type" "object" "properties" {"child" {"type" "ref" "ref" "#entry"}
                                                                   "label" {"type" "string"}}}}}}
        calls (atom []) catalog (schema/record-catalog! #(do (swap! calls conj %) (get docs %)) id)]
    (is (= [id external] @calls))
    (is (= "valid" (lexicon/validate-record! catalog id "self" {"$type" id "value" {"child" {"label" "hi"}}} true)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lexicon/validate-record! catalog id "self" {"$type" id "value" {"child" {"label" 2}}} true)))
    (doseq [ref ["#missing" "https://example.com/schema" "com.example.defs#" "com.example.defs#a#b" "#"]]
      (is (rejects? #(schema/record-catalog! (assoc-in docs [id "defs" "main" "record" "properties" "value" "ref"] ref) id))))
    (doseq [type ["token" "unknown" "ref" "union" "query" "params" "permission-set"]]
      (is (rejects? #(schema/record-catalog! (assoc-in docs [external "defs" "entry"] {"type" type}) id)))))
  (let [doc (-> (record-doc {"a" {"type" "ref" "ref" "#text"}
                             "b" {"type" "union" "refs" ["#text"]}})
                (assoc-in ["defs" "text"] {"type" "string"}))]
    (is (rejects? #(schema/record-catalog! {id doc} id)) "A prior ref visit cannot bypass union target checking"))
  (let [other "com.example.child"
        catalog (schema/record-catalog! {id (record-doc {"child" {"type" "ref" "ref" other}})
                                        other (assoc (record-doc {}) "id" other)} id)]
    (is (= "valid" (lexicon/validate-record! catalog id "self" {"$type" id "child" {"$type" other}} true)))
    (doseq [child [{} {"$type" id} {"$type" (str other "#main")}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (lexicon/validate-record! catalog id "self" {"$type" id "child" child} true))))))

(deftest only-reachable-definitions-are-included
  (let [doc (assoc-in (record-doc {}) ["defs" "unused"] {"type" "future"})
        catalog (schema/record-catalog! {id doc} id)]
    (is (= #{"main"} (set (keys (get-in catalog [id "defs"])))))
    (is (rejects? #(schema/record-catalog! {id (assoc-in doc ["defs" "main" "record" "properties" "bad"]
                                                     {"type" "ref" "ref" "#unused"})} id))))
  (doseq [doc [nil {} (assoc (record-doc {}) "id" "com.wrong.note")
              (assoc (record-doc {}) "lexicon" 2)
              (assoc-in (record-doc {}) ["defs" "main" "key"] "literal:..")
              (assoc-in (record-doc {}) ["defs" "main"] {"type" "object" "properties" {}})]]
    (is (rejects? #(schema/record-catalog! {id doc} id)))))

(deftest graph-work-is-bounded
  (let [calls (atom 0)
        lookup (fn [id]
                 (swap! calls inc)
                 (let [n (Long/parseLong (subs id (count "com.example.n")))]
                   {"lexicon" 1 "id" id "defs"
                    {"main" {"type" "record" "key" "any" "record"
                             {"type" "object" "properties" {"next" {"type" "ref" "ref" (str "com.example.n" (inc n))}}}}}}))]
    (is (rejects? #(schema/record-catalog! lookup "com.example.n0")))
    (is (<= @calls 32)))
  (let [large (record-doc (into {} (for [i (range 10001)] [(str "p" i) {"type" "string"}])))]
    (is (rejects? #(schema/record-catalog! {id large} id))))
  (is (rejects? #(schema/record-catalog! {id (assoc (record-doc {}) "description" (apply str (repeat 1000000 "a")))} id))))
