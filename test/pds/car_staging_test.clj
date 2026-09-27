(ns pds.car-staging-test
  (:require [clojure.test :refer [deftest is]]
            [pds.car-staging :as staging]
            [pds.car-test :as ct]
            [pds.crypto :as crypto]
            [pds.protocol.codec :as codec]
            [pds.protocol.mst :as mst]
            [pds.protocol.repository :as repository]
            [pds.repository-test :as rt]
            [pds.tempfile :as tempfile])
  (:import [java.io ByteArrayInputStream IOException]
           [java.nio ByteBuffer]))

(deftest staged-blocks-deduplicate-payloads-and-obey-the-owners-lifetime
  (let [data (codec/encode {"hello" "disk"}) cid (codec/cid data)
        bytes (ct/archive cid [[cid data] [cid data]]) loader (atom nil)]
    (with-open [channel (tempfile/open-channel!)]
      (let [result (staging/stage! channel (ByteArrayInputStream. bytes) (alength bytes))]
        (reset! loader (:load-block result))
        (is (= [cid] (:roots result)))
        (is (= 2 (:block-count result)))
        (is (= (alength bytes) (:size result)))
        (is (= (alength data) (.size channel)))
        (is (= (vec data) (vec (@loader cid))))
        (is (nil? (@loader (codec/cid (byte-array [0])))))
        (.write channel (ByteBuffer/wrap (byte-array [0])) 0)
        (is (thrown? IOException (@loader cid)) "Disk bytes are rehashed on every load")))
    (is (thrown? IOException (@loader cid)))))

(deftest staging-failure-leaves-cleanup-to-the-channel-owner
  (with-open [channel (tempfile/open-channel!)]
    (let [data (codec/encode {}) cid (codec/cid data)
          bytes (byte-array (concat (seq (ct/archive cid [[cid data]])) [-128]))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (staging/stage! channel (ByteArrayInputStream. bytes) 10000)))
      (is (.isOpen channel))
      (is (= (alength data) (.size channel)))
      (is (thrown? IllegalArgumentException
                   (staging/stage! channel (ByteArrayInputStream. bytes) 10000))))))

(deftest disk-loaded-repositories-verify-without-buffered-tree-or-record-maps
  (doseq [algorithm ["ES256" "ES256K"]]
    (let [key (crypto/keypair algorithm)
          values (into {} (for [i (range 40)] [(str "com.example.record/" i) {"n" i "text" (apply str (repeat 10000 "a"))}]))
          fixture (rt/fixture key values) expected (repository/verify-car (:car fixture) rt/did key)]
      (with-open [channel (tempfile/open-channel!)]
        (let [{:keys [roots load-block]} (staging/stage! channel (ByteArrayInputStream. (:car fixture)) (* 1024 1024))]
          (with-redefs [mst/build (fn [& _] (throw (AssertionError. "Buffered builder forbidden")))
                        mst/read-tree (fn [& _] (throw (AssertionError. "Buffered traversal forbidden")))]
            (let [verified (repository/verify-blocks roots load-block rt/did key)]
              (is (= (select-keys expected [:head :rev :root :paths])
                     (select-keys verified [:head :rev :root :paths])))
              (is (= (set (keys (:blocks expected))) (:block-cids verified)))
              (is (not (contains? verified :records)))
              (is (not (contains? verified :blocks)))))
          (is (thrown? Exception (repository/verify-blocks roots load-block rt/did (crypto/keypair algorithm)))))))))
