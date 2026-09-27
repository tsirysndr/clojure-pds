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

(deftest rootless-block-cars
  (let [record (codec/encode {"$type" "com.example.record"}) cid (codec/cid record)
        decoded (car/decode (car/encode nil {cid record}))]
    (is (= [] (:roots decoded)))
    (is (= (vec record) (vec (get-in decoded [:blocks cid])))))
  (is (= {:roots [] :blocks {}} (car/decode (car/encode nil {})))))

(deftest inclusion-and-absence-proofs
  (let [record (codec/encode {"$type" "com.example.record"}) cid (codec/cid record)
        keys (mapv #(str "com.example.record/" %) (range 200))
        tree (mst/build (zipmap keys (repeat cid))) blocks (assoc (:blocks tree) cid record)]
    (doseq [key (conj keys "com.example.record/missing" "com.example.record/!" "com.example.record/zzz")]
      (let [proof (mst/proof (:root tree) key blocks)]
        (is (= (when (some #{key} keys) cid) (:cid proof)))
        (is (< (count (:blocks proof)) (count blocks)))
        ;; Every block needed to re-traverse the proof is included.
        (is (= (:cid proof) (:cid (mst/proof (:root tree) key (:blocks proof)))))))
    (is (thrown? Exception (mst/proof (:root tree) (first keys) {})))))

(deftest proof-visitor-validates-before-visiting-and-can-stop-traversal
  (let [record (codec/encode {"$type" "com.example.record" "n" 1}) cid (codec/cid record)
        tree (mst/build {"com.example.record/a" cid}) blocks (assoc (:blocks tree) cid record)
        visited (atom [])]
    (is (= cid (mst/visit-proof! (:root tree) "com.example.record/a" blocks
                                (fn [cid _] (swap! visited conj cid)) false)))
    (is (= [(:root tree)] @visited))
    (reset! visited [])
    (is (thrown? Exception
          (mst/visit-proof! (:root tree) "com.example.record/a" (assoc blocks cid (byte-array [0]))
                            (fn [cid _] (swap! visited conj cid)) true)))
    (is (= [(:root tree)] @visited) "A corrupt leaf is never passed to the visitor")
    (let [loaded (atom [])]
      (is (thrown-with-msg? Exception #"Stop writing"
            (mst/visit-proof! (:root tree) "com.example.record/a"
                              #(do (swap! loaded conj %) (get blocks %))
                              (fn [_ _] (throw (ex-info "Stop writing" {}))) true)))
      (is (= [(:root tree)] @loaded)))))
