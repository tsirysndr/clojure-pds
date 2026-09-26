(ns pds.request-test
  (:require [clojure.test :refer [deftest is]]
            [pds.request :as request]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax])
  (:import [java.io ByteArrayInputStream]))
(defn body [value]
  {:headers {"content-type" "application/json"} :body (ByteArrayInputStream. (codec/utf8 value))})
(deftest bounded-json
  (is (= {"a" "[{}]"} (request/json-body (body "{\"a\":\"[{}]\"}"))))
  (is (thrown? Exception (request/json-body (body (str "{\"a\":" (apply str (repeat 10000 "[")) "1" (apply str (repeat 10000 "]")) "}")))))
  (is (thrown? Exception (request/json-body (body "[]")))))

(deftest exactly-one-json-value
  (is (= [] (request/json-value (codec/utf8 "[] \n"))))
  (doseq [text ["{}{}" "{} null" "[]garbage"]]
    (is (thrown? Exception (request/json-value (codec/utf8 text)))))
  (is (thrown? Exception (request/json-body (body "{}{}")))))
(deftest union-type-references
  (is (syntax/type-ref? "app.bsky.richtext.facet#link"))
  (is (not (syntax/type-ref? "app.bsky.richtext.facet#main")))
  (is (not (syntax/type-ref? "app.bsky.richtext.facet#")))
  (let [value {"$type" "app.bsky.richtext.facet#link" "uri" "https://example.com"}]
    (is (= value (codec/to-json (codec/decode (codec/encode (codec/from-json value))))))))

(deftest declared-array-query-parameters
  (is (= {"did" "did:web:a.test" "cids" ["one" "two"]}
         (request/query-params {:query-string "did=did%3Aweb%3Aa.test&cids=one&cids=two"} #{"cids"})))
  (is (thrown? Exception (request/query-params {:query-string "did=a&did=b"} #{"cids"})))
  (is (thrown? Exception (request/query-params {:query-string "cids=a&cids=b"})))
  (is (= {"cids" ["one"]} (request/query-params {:query-string "cids=one"} #{"cids"}))))
