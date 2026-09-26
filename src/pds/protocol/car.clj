(ns pds.protocol.car
  (:require [pds.protocol.codec :as codec])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util Arrays]))

(defn- write-varint! [^ByteArrayOutputStream out n]
  (loop [n n]
    (if (< n 128) (.write out (int n))
        (do (.write out (bit-or 128 (bit-and 127 n))) (recur (unsigned-bit-shift-right n 7))))))
(defn- frame! [^ByteArrayOutputStream out ^bytes data]
  (write-varint! out (alength data)) (.write out data))

(defn encode [root blocks]
  (let [out (ByteArrayOutputStream.)]
    (frame! out (codec/encode {"version" 1 "roots" (if root [(codec/link root)] [])}))
    (doseq [id (concat (when root [root]) (sort (disj (set (keys blocks)) root)))]
      (let [data (get blocks id)]
        (when-not (and data (= id (codec/cid (aget (codec/cid-bytes id) 1) data)))
          (codec/fail! "Missing or corrupt CAR block"))
        (let [cid-data (codec/cid-bytes id)]
          (write-varint! out (+ (alength cid-data) (alength ^bytes data)))
          (.write out cid-data) (.write out ^bytes data))))
    (.toByteArray out)))

(defn- read-varint [^ByteArrayInputStream in]
  (loop [shift 0 result 0]
    (when (> shift 28) (codec/fail! "CAR frame too large"))
    (let [b (.read in)]
      (when (= b -1) (codec/fail! "Truncated CAR varint"))
      (let [result (bit-or result (bit-shift-left (bit-and b 127) shift))]
        (if (< b 128)
          (do (when (and (pos? shift) (zero? b)) (codec/fail! "Noncanonical varint")) result)
          (recur (+ shift 7) result))))))

(defn- frame [^ByteArrayInputStream in]
  (let [n (read-varint in)]
    (when (or (zero? n) (> n (.available in)) (> n (+ 36 (* 1024 1024))))
      (codec/fail! "Invalid CAR frame length"))
    (.readNBytes in n)))

(defn decode [^bytes bytes]
  (when (> (alength bytes) (* 64 1024 1024)) (codec/fail! "CAR exceeds 64 MiB"))
  (let [in (ByteArrayInputStream. bytes)
        header (codec/decode (frame in))
        roots (get header "roots")]
    (when-not (and (= 1 (get header "version")) (vector? roots)
                   (every? #(instance? pds.protocol.codec.Link %) roots))
      (codec/fail! "Invalid CAR v1 header"))
    {:roots (mapv :cid roots)
     :blocks (loop [blocks {}]
               (if (zero? (.available in)) blocks
                   (let [data (frame in)]
                     (when (< (alength data) 36) (codec/fail! "Truncated CID"))
                     (let [id (str "b" (codec/base32 (Arrays/copyOfRange data 0 36)))
                           cid-data (codec/cid-bytes id)
                           block (Arrays/copyOfRange data 36 (alength data))]
                       (when-not (= id (codec/cid (aget cid-data 1) block)) (codec/fail! "CAR block hash mismatch"))
                       (recur (assoc blocks id block))))))}))
