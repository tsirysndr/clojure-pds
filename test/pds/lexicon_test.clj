(ns pds.lexicon-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [pds.lexicon :as lexicon]
            [pds.protocol.codec :as codec]
            [pds.protocol.formats :as formats]))

(defn fixture [path] (json/read-str (slurp (io/resource (str "fixtures/" path)))))
(def demo "example.lexicon.record")
(def demo-catalog {demo (fixture "lexicon/catalog/record.json")})
(def raw-cid (codec/cid 85 (byte-array [1 2 3])))

(deftest application-constraints-still-apply-to-empty-blobs
  (let [native (codec/from-json {"$type" "blob" "ref" {"$link" (codec/cid 85 (byte-array 0))}
                                 "mimeType" "application/octet-stream" "size" 0})
        schema {"type" "blob" "accept" ["*/*"] "maxSize" 10}]
    (is (= native (lexicon/validate! {} "com.example.file" schema native)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lexicon/validate! {} "com.example.file" (assoc schema "minSize" 1) native)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lexicon/validate! {} "com.example.file" (assoc schema "accept" ["image/*"]) native)))))
(defn valid-demo! [record]
  (lexicon/validate-record! demo-catalog demo "demo" (codec/from-json record) true))
(defn with-raw-blobs [record]
  ;; The unmodified upstream fixture uses DAG-CBOR CIDs for blobs, contrary to
  ;; the current data model. Substitute only the codec for schema-level tests;
  ;; separately assert that the original full record fails data-model validation.
  (walk/postwalk #(if (and (map? %) (= "blob" (get % "$type")))
                    (assoc % "ref" {"$link" raw-cid}) %) record))

(deftest upstream-record-fixtures
  (doseq [row (fixture "lexicon/record-data-valid.json")]
    (testing (get row "name")
      (is (= "valid" (valid-demo! (with-raw-blobs (get row "data")))))
      (when (= "full" (get row "name"))
        (is (thrown? clojure.lang.ExceptionInfo (codec/from-json (get row "data")))))))
  (doseq [row (fixture "lexicon/record-data-invalid.json")]
    (testing (get row "name")
      ;; Correct the blob codec and fill the mandatory field so unrelated
      ;; missing-field failures cannot mask the intended invalid constraint.
      (let [data (with-raw-blobs (get row "data"))
            data (if (= "missing required field" (get row "name")) data
                     (merge {"integer" 1} data))]
        (is (thrown? clojure.lang.ExceptionInfo (valid-demo! data)))))))

(deftest upstream-string-formats
  (doseq [[kind valid?] {"datetime" formats/datetime? "uri" formats/uri? "language" formats/language?}
          [outcome expected] [["valid" true] ["invalid" false]]
          line (str/split-lines (slurp (io/resource (str "fixtures/syntax/" kind "_syntax_" outcome ".txt"))))
          :when (and (not (str/blank? line)) (not (str/starts-with? line "#")))]
    (testing (str kind " " (pr-str line)) (is (= expected (valid? line)))))
  (doseq [kind ["datetime" "language"]
          line (str/split-lines (slurp (io/resource (str "fixtures/syntax/" kind "_parse_invalid.txt"))))
          :when (and (not (str/blank? line)) (not (str/starts-with? line "#")))]
    (is (false? ((formats/validators kind) line)) line)))

(deftest schema-semantics
  (let [validate #(lexicon/validate! demo-catalog demo %1 %2)]
    (is (= false (validate {"type" "boolean" "const" false} false)))
    (is (thrown? clojure.lang.ExceptionInfo (validate {"type" "boolean" "const" false} true)))
    (is (= "é" (validate {"type" "string" "maxLength" 2} "é")))
    (is (thrown? clojure.lang.ExceptionInfo (validate {"type" "string" "maxLength" 1} "é")))
    (is (= "👨‍👩‍👧‍👦" (validate {"type" "string" "maxGraphemes" 1} "👨‍👩‍👧‍👦")))
    (is (= "new" (validate {"type" "string" "knownValues" ["old"]} "new")))
    (is (= {} (validate {"type" "object" "properties" {"n" {"type" "integer" "default" 42}}} {})))
    (is (= {"extra" nil} (validate {"type" "object" "properties" {}} {"extra" nil})))
    (doseq [value [nil true [] (codec/link raw-cid) (byte-array [1]) {"$type" "blob"}]]
      (is (thrown? clojure.lang.ExceptionInfo (validate {"type" "unknown"} value))))
    (doseq [value [{"$type" "com.example.future" "data" 1} {"$type" "example.lexicon.record#demoObject" "a" 1}]]
      (is (= value (validate {"type" "union" "refs" ["#demoObject"]} value))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (validate {"type" "union" "refs" ["#demoObject"]}
                           {"$type" "example.lexicon.record#demoObject" "a" "wrong"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (validate {"type" "union" "refs" ["#demoObject"] "closed" true}
                           {"$type" "com.example.future"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (validate {"type" "union" "refs" []} {"$type" "com.example.future#main"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lexicon/validate! {"com.example.recursive" {"defs" {"main" {"type" "ref" "ref" "#main"}}}}
                                    "com.example.recursive" {"type" "ref" "ref" "#main"} {})))))

(deftest pinned-catalog-and-validation-modes
  (is (= 101 (count (lexicon/load-catalog))))
  (doseq [[id schema] @lexicon/catalog
          node (tree-seq coll? #(if (map? %) (vals %) (seq %)) schema)
          :when (map? node)
          ref (concat (when (string? (get node "ref")) [(get node "ref")]) (get node "refs"))]
    (is (some? (second (lexicon/definition @lexicon/catalog id ref))) ref))
  (let [post {"$type" "app.bsky.feed.post" "text" "Hello" "createdAt" "2026-09-26T00:00:00Z"}
        check #(lexicon/validate-record! "app.bsky.feed.post" "3l4aaaaaaa222" %1 %2)]
    (is (= "valid" (check post nil) (check post true)))
    (is (nil? (check (dissoc post "createdAt") false)))
    (is (thrown? clojure.lang.ExceptionInfo (check (dissoc post "createdAt") nil)))
    (is (thrown? clojure.lang.ExceptionInfo (check (assoc post "text" (apply str (repeat 301 "a"))) true)))
    (is (thrown? clojure.lang.ExceptionInfo (lexicon/validate-record! "app.bsky.feed.post" "self" post true))))
  (is (= "valid" (lexicon/validate-record! "app.bsky.actor.profile" "self" {"$type" "app.bsky.actor.profile"} nil)))
  (is (thrown? clojure.lang.ExceptionInfo (lexicon/validate-record! "app.bsky.actor.profile" "another" {} true)))
  (is (= "unknown" (lexicon/validate-record! "com.example.newRecord" "any" {} nil)))
  (is (thrown? clojure.lang.ExceptionInfo (lexicon/validate-record! "com.example.newRecord" "any" {} true)))
  (is (nil? (lexicon/validate-record! "com.example.newRecord" "any" {} false))))
