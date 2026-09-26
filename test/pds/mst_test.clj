(ns pds.mst-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.mst :as mst]))
(defn fixtures [path] (json/read-str (slurp (io/resource (str "fixtures/" path ".json")))))
(deftest upstream-mst-vectors
  (doseq [f (fixtures "mst/key_heights")]
    (is (= (get f "height") (mst/height (get f "key")))))
  (doseq [f (fixtures "mst/common_prefix")]
    (is (= (get f "len") (mst/common-prefix (codec/utf8 (get f "left")) (codec/utf8 (get f "right"))))))
  (doseq [f (fixtures "firehose/commit-proof-fixtures")]
    (testing (get f "comment")
      (let [before (zipmap (get f "keys") (repeat (get f "leafValue")))
            after (apply dissoc (merge before (zipmap (get f "adds") (repeat (get f "leafValue")))) (get f "dels"))]
        (is (= (get f "rootBeforeCommit") (:root (mst/build before))))
        (is (= (get f "rootAfterCommit") (:root (mst/build after))))))))
(deftest car-integrity
  (let [{:keys [root blocks]} (mst/build {})
        data (car/encode root blocks)
        decoded (car/decode data)]
    (is (= [root] (:roots decoded)))
    (is (= (vec (get blocks root)) (vec (get-in decoded [:blocks root]))))
    (aset-byte data (dec (alength data)) (unchecked-byte 255))
    (is (thrown? Exception (car/decode data)))))
