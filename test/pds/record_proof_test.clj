(ns pds.record-proof-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.plc :as plc]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.mst :as mst]
            [pds.protocol.repository :as repository]
            [pds.repository-test :as fixture])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(defn slice [f path]
  (let [proof (mst/proof (:root f) path (:blocks f))]
    (car/encode (:head f) (assoc (:blocks proof) (:head f) (get (:blocks f) (:head f))))))

(deftest partial-record-inclusion-and-absence-on-both-curves
  (doseq [algorithm ["ES256" "ES256K"]]
    (let [key (crypto/keypair algorithm)
          values (into {} (for [i (range 300)] [(str "com.example.record/" i) {"number" i}]))
          f (fixture/fixture key values)]
      (doseq [rkey ["0" "1" "33" "299" "absent"]]
        (let [path (str "com.example.record/" rkey) bytes (slice f path)
              verified (repository/verify-record bytes fixture/did key "com.example.record" rkey)]
          (is (= (get values path) (:record verified)))
          (is (= (:head f) (:head verified)))
          (is (< (count (:blocks (car/decode bytes))) (count (:blocks f)))))))))

(deftest missing-corrupt-unconnected-and-wrongly-signed-proofs
  (let [key (crypto/keypair) f (fixture/fixture key {"com.example.record/a" {"a" true}})
        bytes (slice f "com.example.record/a") {:keys [roots blocks]} (car/decode bytes)
        verify #(repository/verify-record % fixture/did key "com.example.record" "a")]
    (doseq [cid (keys blocks) :when (not= cid (first roots))]
      (is (thrown? Exception (verify (car/encode (first roots) (dissoc blocks cid))))))
    (is (thrown? Exception (repository/verify-record bytes "did:web:wrong.example.com" key "com.example.record" "a")))
    (is (thrown? Exception (repository/verify-record bytes fixture/did (crypto/keypair) "com.example.record" "a")))
    (let [corrupt (aclone bytes)]
      (aset-byte corrupt (dec (alength corrupt)) (unchecked-byte (bit-xor 1 (aget corrupt (dec (alength corrupt))))))
      (is (thrown? Exception (verify corrupt))))
    (let [empty (fixture/fixture key {})
          with-extra (car/encode (:head empty) (merge blocks (:blocks empty)))]
      (is (nil? (:record (verify with-extra))) "A disconnected record is not an inclusion proof"))
    (is (thrown? Exception (repository/verify-record bytes fixture/did key "invalid" "a")))))

(deftest signed-malformed-path-nodes-are-rejected
  (let [key (crypto/keypair) value (codec/encode {}) cid (codec/cid value)
        a (fixture/entry "com.example.record/a" cid) b (fixture/entry "com.example.record/b" cid)]
    (doseq [node [{"e" [a]} {"l" nil "e" [b a]} {"l" nil "e" [a a]}
                  {"l" nil "e" [(assoc a "p" 1)]} {"l" nil "e" [(assoc a "v" nil)]}
                  {"l" nil "e" [(assoc a "t" "invalid")]}
                  {"l" nil "e" [(assoc a "k" (codec/utf8 "com.example.record/é"))]}
                  ;; Ordered but non-maximal compression is also invalid.
                  {"l" nil "e" [a b]}]]
      (let [f (fixture/signed-car key (fixture/node node {cid value}) {})]
        (is (thrown? Exception (repository/verify-record (:car f) fixture/did key "com.example.record" "a")) (pr-str node))))
    (let [child (fixture/node {"l" nil "e" [b]} {cid value})
          tree (fixture/node {"l" (codec/link (:root child)) "e" [a]} (:blocks child))
          f (fixture/signed-car key tree {})]
      (is (thrown? Exception (repository/verify-record (:car f) fixture/did key "com.example.record" "0")) "Child keys must respect the parent's bounds"))))

(deftest upstream-generated-partial-proofs-verify-locally
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [path (Files/createTempFile "pds-proofs-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/generate-proofs.mjs" (str path)]) (.redirectErrorStream true)))
              finished? (.waitFor process 30 TimeUnit/SECONDS)]
          (when-not finished? (.destroyForcibly process))
          (is finished?)
          (when finished?
            (is (= 0 (.exitValue process)) (slurp (.getInputStream process)))
            (when (zero? (.exitValue process))
              (doseq [f (json/read-str (slurp (str path)))]
                (let [result (repository/verify-record (crypto/unb64 (get f "car")) (get f "did")
                                                       (plc/parse-key (get f "didKey")) "com.example.record" (get f "rkey"))]
                  (is (= (get f "record") (:record result)))
                  (is (= (get f "head") (:head result))))))))
        (finally (Files/deleteIfExists path))))))
