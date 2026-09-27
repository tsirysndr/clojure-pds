(ns pds.protocol.car
  (:require [pds.protocol.codec :as codec])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream OutputStream]))

(defn- write-varint! [^OutputStream out n]
  (loop [n n]
    (if (< n 128) (.write out (int n))
        (do (.write out (bit-or 128 (bit-and 127 n))) (recur (unsigned-bit-shift-right n 7))))))
(defn- frame! [^OutputStream out ^bytes data]
  (write-varint! out (alength data)) (.write out data))

(defn write-header! [out root]
  (frame! out (codec/encode {"version" 1 "roots" (if root [(codec/link root)] [])})))

(defn write-block! [^OutputStream out id data]
  (when-not (and (bytes? data) (= id (codec/cid (aget (codec/cid-bytes id) 1) data)))
    (codec/fail! "Missing or corrupt CAR block"))
  (let [cid-data (codec/cid-bytes id)]
    (write-varint! out (+ (alength cid-data) (alength ^bytes data)))
    (.write out cid-data) (.write out ^bytes data)))

(defn write!
  "Write a CAR to a caller-owned OutputStream without closing it."
  [out root blocks]
  (write-header! out root)
  (doseq [id (concat (when root [root]) (sort (disj (set (keys blocks)) root)))]
    (write-block! out id (get blocks id))))

(defn encode [root blocks]
  (let [out (ByteArrayOutputStream.)]
    (write! out root blocks)
    (.toByteArray out)))

(def max-size (* 64 1024 1024))
(def ^:private max-frame-size (+ 36 (* 1024 1024)))

(defn visit!
  "Read hash-checked CAR v1 blocks from a caller-owned InputStream. Visits every
  occurrence in wire order, including duplicates, without retaining payloads.
  Returns roots, encoded size and block count after EOF. The byte limit includes
  the header, frame lengths and duplicate blocks. A visitor may see valid blocks
  before a later error: callers must stage changes until the whole CAR verifies.
  Supports this PDS's CIDv1 SHA-256 raw/CBOR subset; never closes the input."
  ([input visit-block!] (visit! input max-size visit-block!))
  ([^InputStream input maximum visit-block!]
   (when-not (and (integer? maximum) (<= 1 maximum Long/MAX_VALUE))
     (throw (IllegalArgumentException. "CAR byte limit must be a positive long")))
   (let [consumed (volatile! 0)]
     (letfn [(check-thread! []
               (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.))))
             (too-large! []
               (throw (ex-info "CAR exceeds the byte limit" {:car-too-large true})))
             (read-byte! []
               (check-thread!)
               (let [b (.read input)]
                 (when-not (= b -1)
                   (when (>= @consumed maximum) (too-large!))
                   (vswap! consumed inc))
                 b))
             (length! [first-byte]
               (loop [b first-byte shift 0 result 0]
                 (when (= b -1) (codec/fail! "Truncated CAR varint"))
                 (let [result (bit-or result (bit-shift-left (bit-and b 127) shift))]
                   (if (< b 128)
                     (do (when (and (pos? shift) (zero? b)) (codec/fail! "Noncanonical varint"))
                         (when (or (zero? result) (> result max-frame-size))
                           (codec/fail! "Invalid CAR frame length"))
                         ;; Reject before allocation or reading the frame payload.
                         (when (> result (- maximum @consumed)) (too-large!))
                         result)
                     (do (when (>= shift 28) (codec/fail! "CAR frame too large"))
                         (recur (read-byte!) (+ shift 7) result))))))
             (bytes! [n]
               (let [data (byte-array n)]
                 (loop [offset 0]
                   (if (= offset n) data
                     (do (check-thread!)
                         (let [count (.read input data offset (min 65536 (- n offset)))]
                           (when (<= count 0) (codec/fail! "Truncated CAR frame"))
                           (vswap! consumed + count)
                           (recur (+ offset count))))))))]
       (let [header (codec/decode (bytes! (length! (read-byte!))))
             roots (get header "roots")]
         (when-not (and (= 1 (get header "version")) (vector? roots)
                        (every? #(instance? pds.protocol.codec.Link %) roots))
           (codec/fail! "Invalid CAR v1 header"))
         (loop [count 0]
           (let [first-byte (read-byte!)]
             (if (= first-byte -1)
               {:roots (mapv :cid roots) :size @consumed :block-count count}
               (let [n (length! first-byte)]
                 (when (< n 36) (codec/fail! "Truncated CID"))
                 (let [id (str "b" (codec/base32 (bytes! 36)))
                       cid-data (codec/cid-bytes id)
                       block (bytes! (- n 36))]
                   (when-not (= id (codec/cid (aget cid-data 1) block))
                     (codec/fail! "CAR block hash mismatch"))
                   (visit-block! id block)
                   (recur (inc count))))))))))))

(defn decode [^bytes bytes]
  (when (> (alength bytes) max-size) (codec/fail! "CAR exceeds 64 MiB"))
  (let [blocks (atom {})
        result (visit! (ByteArrayInputStream. bytes) #(swap! blocks assoc %1 %2))]
    {:roots (:roots result) :blocks @blocks}))
