(ns pds.repository-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.plc :as plc]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.mst :as mst]
            [pds.protocol.repository :as repository]
            [pds.protocol.syntax :as syntax])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(def did "did:web:source.example.net")
(def next-tid (syntax/tid-generator))
(defn signed-car [key tree commit-fields]
  (let [unsigned (merge {"did" did "version" 3 "rev" (next-tid) "prev" nil "data" (codec/link (:root tree))} commit-fields)
        commit (assoc unsigned "sig" (crypto/sign (:algorithm key) (:private key) (codec/encode unsigned)))
        bytes (codec/encode commit) head (codec/cid bytes)]
    (assoc tree :head head :commit commit :blocks (assoc (:blocks tree) head bytes)
           :car (car/encode head (assoc (:blocks tree) head bytes)))))
(defn fixture [key values]
  (let [blocks (into {} (map (fn [[_ value]] (let [data (codec/encode value)] [(codec/cid data) data]))) values)
        paths (into {} (map (fn [[path value]] [path (codec/cid (codec/encode value))])) values)
        tree (mst/build paths)]
    (signed-car key (update tree :blocks merge blocks) {})))
(defn node [value blocks]
  (let [data (codec/encode value) root (codec/cid data)] {:root root :blocks (assoc blocks root data)}))
(defn entry [path cid]
  {"p" 0 "k" (codec/utf8 path) "v" (codec/link cid) "t" nil})
(defn rejected? [f] (try (f) false (catch Exception _ true)))

(deftest complete-repositories-verify-on-both-curves
  (doseq [algorithm ["ES256" "ES256K"] n [0 1 300]]
    (let [key (crypto/keypair algorithm)
          values (into {} (for [i (range n)] [(str "com.example.record/" i) {"$type" "com.example.record" "n" i}]))
          f (fixture key values) verified (repository/verify-car (:car f) did key)]
      (is (= (:head f) (:head verified)))
      (is (= n (count (:paths verified))))
      (is (= (set (keys (:blocks f))) (set (keys (:blocks verified)))))
      (is (= values (into {} (map (fn [{:keys [collection rkey cid]}] [(str collection "/" rkey) (get (:records verified) cid)])) (:paths verified)))))))

(deftest repository-identity-and-commit-validation
  (let [key (crypto/keypair "ES256") f (fixture key {})
        unsigned (dissoc (:commit f) "sig") tree (mst/build {})]
    (is (rejected? #(repository/verify-car (:car f) "did:web:other.example.net" key)))
    (is (rejected? #(repository/verify-car (:car f) did (crypto/keypair "ES256"))))
    (doseq [fields [{"version" 2} {"did" "bad"} {"rev" "not-a-tid"} {"data" nil} {"extra" 1}
                    {"prev" (codec/link (codec/cid 85 (byte-array [1])))}
                    {"rev" (syntax/encode-tid (* 1024 1000 (+ (System/currentTimeMillis) 600000)))}]]
      (is (rejected? #(repository/verify-car (:car (signed-car key tree fields)) did key)) (pr-str fields)))
    (doseq [field (keys unsigned)]
      (let [data (codec/encode (dissoc (:commit f) field)) id (codec/cid data)]
        (is (rejected? #(repository/verify-car (car/encode id (assoc (:blocks f) id data)) did key)))))
    (is (rejected? #(repository/verify-car (car/encode nil {}) did key)))
    (let [data (codec/encode (assoc (:commit f) "sig" (byte-array 64))) id (codec/cid data)]
      (is (rejected? #(repository/verify-car (car/encode id (assoc (:blocks f) id data)) did key))))))

(deftest unrelated-car-blocks-and-record-links-do-not-become-owned
  (let [key (crypto/keypair "ES256K") foreign (codec/encode {"secret" true}) foreign-cid (codec/cid foreign)
        blob (byte-array [1 2 3]) blob-cid (codec/cid 85 blob)
        value {"$type" "com.example.unknown" "reference" (codec/link foreign-cid)
               "blob" {"$type" "blob" "ref" (codec/link blob-cid) "mimeType" "image/png" "size" 3}
               "legacy" {"cid" blob-cid "mimeType" "image/png"}}
        ;; Import preserves historical objects without imposing current Lexicons.
        f (fixture key {"com.example.record/one" value "com.example.record/two" value})
        extras (assoc (:blocks f) foreign-cid foreign blob-cid blob)
        verified (repository/verify-car (car/encode (:head f) extras) did key)]
    (is (= 2 (count (:paths verified))))
    (is (= 1 (count (:records verified))))
    (is (= (set (keys (:blocks f))) (set (keys (:blocks verified)))))
    (is (= value (first (vals (:records verified)))))
    (is (= (:head f) (:head (repository/verify-car (:car f) did key))) "External referenced blocks need not be present")))

(deftest untrusted-mst-shape-prefix-order-and-completeness
  (let [key (crypto/keypair "ES256") record (codec/encode {}) cid (codec/cid record) blocks {cid record}
        a (entry "com.example.record/a" cid) b (entry "com.example.record/b" cid)
        child (node {"l" nil "e" [a]} blocks)
        verify #(repository/verify-car (:car (signed-car key % {})) did key)]
    (doseq [bad-node [{"l" nil "e" [] "extra" true}
                      {"e" []}
                      {"l" nil "e" [b a]}
                      {"l" nil "e" [a a]}
                      {"l" nil "e" [(assoc a "p" 1)]}
                      {"l" nil "e" [(assoc a "k" "not bytes")]}
                      {"l" nil "e" [(assoc a "k" (byte-array [(unchecked-byte 255)]))]}
                      {"l" nil "e" [(assoc a "v" nil)]}
                      {"l" nil "e" [(assoc a "t" "not a link")]}
                      {"l" nil "e" [(assoc a "p" -1)]}
                      {"l" nil "e" [(assoc a "p" 1.0)]}
                      {"l" nil "e" [(assoc a "v" (codec/link (codec/cid 85 record)))]}
                      {"l" (codec/link (:root child)) "e" []}
                      {"l" (codec/link (:root child)) "e" [a]}
                      {"l" (codec/link (:root child)) "e" [(assoc b "t" (codec/link (:root child)))]}]]
      ;; A float cannot be encoded at all; both serialization and traversal must reject.
      (is (rejected? #(verify (node bad-node (:blocks child)))) (pr-str bad-node)))
    (let [f (fixture key {"com.example.record/a" {"hello" true}})]
      (doseq [id (keys (:blocks f)) :when (not= id (:head f))]
        (is (rejected? #(repository/verify-car (car/encode (:head f) (dissoc (:blocks f) id)) did key)))))
    ;; The same sorted mappings in a flat node are invalid when their hash
    ;; heights require multiple levels. Even a valid signature cannot bless it.
    (let [entries (mapv #(entry (str "com.example.record/" %) cid) (range 10))]
      (is (rejected? #(verify (node {"l" nil "e" entries} blocks)))))
    (let [deep (reduce (fn [child _] (node {"l" (codec/link (:root child)) "e" []} (:blocks child))) child (range 130))]
      (is (rejected? #(verify deep))))))

(deftest invalid-paths-and-non-object-records
  (let [key (crypto/keypair "ES256")]
    (doseq [path ["invalid" "com.example.record/" "com.example.record/a/b" "com.example.record/.." "/com.example.record/a"]]
      (is (rejected? #(repository/verify-car (:car (fixture key {path {}})) did key))))
    (doseq [value [nil [] 1 "record" (codec/link (codec/cid (byte-array [1])))]]
      (is (rejected? #(repository/verify-car (:car (fixture key {"com.example.record/a" value})) did key))))
    (is (rejected? #(repository/verify-car (:car (fixture key {"com.example.record/a" {"data" (byte-array 1000000)}})) did key)))))

(defn frame! [^ByteArrayOutputStream out ^bytes value]
  (loop [n (alength value)]
    (if (< n 128) (.write out n)
        (do (.write out (bit-or 128 (bit-and n 127))) (recur (unsigned-bit-shift-right n 7)))))
  (.write out value))

(deftest arbitrary-order-duplicate-blocks-and-extra-roots
  (let [key (crypto/keypair "ES256") f (fixture key {"com.example.record/a" {}})
        out (ByteArrayOutputStream.)]
    (frame! out (codec/encode {"version" 1 "roots" [(codec/link (:head f)) (codec/link (:root f))]}))
    (doseq [[cid bytes] (concat (reverse (sort (:blocks f))) (:blocks f))]
      (frame! out (byte-array (concat (codec/cid-bytes cid) bytes))))
    (is (= (:head f) (:head (repository/verify-car (.toByteArray out) did key))))))

(deftest upstream-generated-repositories-verify-locally
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [path (Files/createTempFile "pds-import-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/generate-repositories.mjs" (str path)]) (.redirectErrorStream true)))
              finished? (.waitFor process 30 TimeUnit/SECONDS)]
          (when-not finished? (.destroyForcibly process))
          (is finished?)
          (when finished?
            (is (= 0 (.exitValue process)) (slurp (.getInputStream process)))
            (when (zero? (.exitValue process))
              (doseq [f (json/read-str (slurp (str path)))]
                (let [verified (repository/verify-car (crypto/unb64 (get f "car")) (get f "did") (plc/parse-key (get f "didKey")))]
                  (is (= (get f "head") (:head verified)))
                  (is (= (mapv #(update-keys % keyword) (get f "paths")) (:paths verified))))))))
        (finally (Files/deleteIfExists path))))))
