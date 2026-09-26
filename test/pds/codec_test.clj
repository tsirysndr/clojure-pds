(ns pds.codec-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pds.protocol.codec :as codec])
  (:import [java.util Base64]))

(defn fixtures [name]
  (json/read-str (slurp (io/resource (str "fixtures/data-model/" name ".json")))))

(deftest exact-upstream-encoding
  (doseq [fixture (fixtures "data-model-fixtures")]
    (let [native (codec/from-json (get fixture "json"))
          encoded (codec/encode native)
          expected (.decode (Base64/getDecoder) ^String (get fixture "cbor_base64"))]
      (is (= (vec expected) (vec encoded)))
      (is (= (get fixture "cid") (codec/cid encoded)))
      (is (= (codec/to-json native) (codec/to-json (codec/decode expected)))))))

(deftest data-model-validation
  (doseq [fixture (fixtures "data-model-valid")]
    (testing (get fixture "note") (is (map? (codec/from-json (get fixture "json"))))))
  (doseq [fixture (fixtures "data-model-invalid")]
    (testing (get fixture "note")
      (is (thrown? Exception
                   (let [value (codec/from-json (get fixture "json"))]
                     (when-not (map? value) (codec/fail! "Top-level object required"))))))))

(deftest reject-noncanonical-cbor
  (doseq [data [[24 1] [159 255] [161 1 2] [162 97 97 1 97 97 2]
                [162 97 98 1 97 97 2] [246 0] [217 0 42 64] [99 255 255 255]
                [27 255 255 255 255 255 255 255 255] [95 255]]]
    (is (thrown? Exception (codec/decode (byte-array (map unchecked-byte data))))))
  (doseq [value [Long/MIN_VALUE Long/MAX_VALUE 0 -1 24 255 65536 4294967296]]
    (is (= value (codec/decode (codec/encode value))))))
