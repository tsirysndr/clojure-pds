(ns pds.car-test
  (:require [clojure.test :refer [deftest is]]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream IOException]
           [java.util Arrays]))

(defn archive [root blocks]
  (let [out (ByteArrayOutputStream.)]
    (car/write-header! out root)
    (doseq [[cid data] blocks] (car/write-block! out cid data))
    (.toByteArray out)))

(defn fragmented [data reads closed?]
  (let [input (ByteArrayInputStream. data)]
    (proxy [InputStream] []
      (available [] (throw (AssertionError. "Streaming readers cannot rely on available")))
      (read
        ([] (swap! reads conj 1) (.read input))
        ([buffer offset length]
         (swap! reads conj length)
         (.read input buffer offset (min 3 length))))
      (close [] (reset! closed? true)))))

(deftest fragmented-streams-preserve-wire-order-duplicates-and-input-ownership
  (let [one (codec/encode {"n" 1}) two (codec/encode {"n" 2})
        cid1 (codec/cid one) cid2 (codec/cid two)
        blocks [[cid2 two] [cid1 one] [cid2 two]]
        bytes (archive cid1 blocks) seen (atom []) reads (atom []) closed? (atom false)]
    (with-open [input (fragmented bytes reads closed?)]
      (is (= {:roots [cid1] :size (alength bytes) :block-count 3}
             (car/visit! input #(swap! seen conj [%1 (vec %2)]))))
      (is (= (mapv (fn [[cid data]] [cid (vec data)]) blocks) @seen))
      (is (false? @closed?)))
    (is @closed?)
    (is (every? #(<= % 65536) @reads))
    (let [decoded (car/decode bytes)]
      (is (= [cid1] (:roots decoded)))
      (is (= {cid1 (vec one) cid2 (vec two)} (update-vals (:blocks decoded) vec))))))

(deftest empty-rootless-and-maximum-block-cars
  (let [empty-car (archive nil [])]
    (is (= {:roots [] :size (alength empty-car) :block-count 0}
           (car/visit! (ByteArrayInputStream. empty-car) #(throw (AssertionError. "No blocks"))))))
  (let [data (byte-array (* 1024 1024)) cid (codec/cid 85 data)
        bytes (archive nil [[cid data]]) reads (atom [])
        source (ByteArrayInputStream. bytes)
        input (proxy [InputStream] []
                (read ([] (.read source))
                      ([buffer offset length]
                       (swap! reads conj length) (.read source buffer offset length))))]
    (is (= 1 (:block-count (car/visit! input (alength bytes)
                                     (fn [id block] (is (= cid id)) (is (Arrays/equals data block)))))))
    (is (= 65536 (apply max @reads)))))

(deftest byte-limit-includes-framing-and-duplicate-blocks
  (let [data (codec/encode {"hello" "world"}) cid (codec/cid data)
        once (archive cid [[cid data]]) twice (archive cid [[cid data] [cid data]])]
    (is (= (alength once) (:size (car/visit! (ByteArrayInputStream. once) (alength once) (fn [& _])))))
    (doseq [[bytes maximum] [[once (dec (alength once))] [twice (alength once)] [once 1]]]
      (let [visited (atom []) error (try (car/visit! (ByteArrayInputStream. bytes) maximum (fn [cid _] (swap! visited conj cid)))
                                         nil (catch clojure.lang.ExceptionInfo error error))]
        (is (true? (:car-too-large (ex-data error))))
        (when (= bytes twice) (is (= [cid] @visited))))))
  (doseq [maximum [0 -1 1.5 nil (inc (bigint Long/MAX_VALUE))]]
    (is (thrown? IllegalArgumentException
                 (car/visit! (ByteArrayInputStream. (byte-array 0)) maximum (fn [& _])))))
  (let [data (byte-array 1024) cid (codec/cid 85 data)
        bytes (archive nil [[cid data]]) header (archive nil [])
        input (ByteArrayInputStream. bytes)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (car/visit! input (+ (alength header) 2) (fn [& _]))))
    (is (= (+ 36 (alength data)) (.available input))
        "A frame exceeding the remaining budget is refused before reading its CID or allocating its payload")))

(deftest malformed-streams-fail-without-publishing-unverified-blocks
  (let [data (codec/encode {"text" "valid"}) cid (codec/cid data)
        header (archive cid []) bytes (archive cid [[cid data]])
        concat-bytes (fn [& parts] (byte-array (mapcat seq parts)))
        corrupt (aclone bytes)]
    (aset-byte corrupt (dec (alength corrupt)) (unchecked-byte 255))
    (doseq [bad [(byte-array 0) (byte-array [0]) (byte-array [-128]) (byte-array [-127 0])
                 (byte-array [-128 -128 -128 -128 -128 0])
                 (byte-array [-1 -1 -1 -1 127])
                 (concat-bytes header [0]) (concat-bytes header [-128])
                 (concat-bytes header [35] (repeat 35 0))
                 (concat-bytes header [36] (repeat 36 0))
                 (Arrays/copyOf bytes (dec (alength bytes))) corrupt
                 (byte-array [1 -96])]]
      (let [visited (atom []) closed? (atom false)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (car/visit! (fragmented bad (atom []) closed?) (fn [id block] (swap! visited conj id)))))
        (is (empty? @visited))
        (is (false? @closed?))))
    (let [visited (atom []) appended (concat-bytes bytes [-128])]
      (is (thrown? clojure.lang.ExceptionInfo
                   (car/visit! (ByteArrayInputStream. appended) (fn [id _] (swap! visited conj id)))))
      (is (= [cid] @visited) "Consumers must stage valid prefix blocks until successful EOF"))))

(deftest visitors-and-read-errors-stop-consumption-without-closing-the-caller-stream
  (let [data (codec/encode {}) cid (codec/cid data)
        once (archive cid [[cid data]]) bytes (archive cid [[cid data] [cid data]])
        input (ByteArrayInputStream. bytes) failure (ex-info "Visitor failed" {})]
    (is (identical? failure (try (car/visit! input (fn [& _] (throw failure)))
                                (catch Exception error error))))
    (is (= (- (alength bytes) (alength once)) (.available input))))
  (let [failure (IOException. "Read failed")]
    (is (identical? failure (try (car/visit! (proxy [InputStream] [] (read [] (throw failure))) (fn [& _]))
                                (catch Exception error error)))))
  (let [zero-reader (proxy [InputStream] []
                      (read ([] 1) ([buffer offset length] 0)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Truncated CAR frame"
                          (car/visit! zero-reader (fn [& _])))))
  (try
    (.interrupt (Thread/currentThread))
    (is (thrown? InterruptedException
                 (car/visit! (ByteArrayInputStream. (archive nil [])) (fn [& _]))))
    (finally (Thread/interrupted))))
