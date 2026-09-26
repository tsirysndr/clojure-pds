(ns pds.blob-refs-test
  (:require [clojure.test :refer [deftest is]]
            [pds.blob-refs :as refs]
            [pds.protocol.codec :as codec]))

(defn blob [data]
  {"$type" "blob" "ref" (codec/link (codec/cid 85 data)) "mimeType" "image/png" "size" (alength ^bytes data)})

(deftest nested-modern-and-legacy-references-without-arbitrary-links
  (let [a (blob (byte-array [1])) b (blob (byte-array [2]))
        a-cid (:cid (get a "ref")) b-cid (:cid (get b "ref"))]
    (is (= #{a-cid b-cid} (refs/references {"nested" [a {"a" a "b" b}]
                                          "legacy" {"cid" a-cid "mimeType" "image/png"}})))
    (is (= #{b-cid} (refs/references {"cid" b-cid "mimeType" "image/png"})))
    (doseq [value [nil a-cid (get a "ref") {"arbitrary" (get a "ref")} {"cid" a-cid}
                   (assoc a "size" 0) (assoc a "size" "1") (assoc a "ref" a-cid)
                   (assoc a "mimeType" "") (assoc a "ref" (codec/link (codec/cid (byte-array [1]))))
                   {"cid" "invalid" "mimeType" "image/png"} {"$type" "com.example.other" "cid" a-cid "mimeType" "image/png"}]]
      (is (= #{} (refs/references value))))))
