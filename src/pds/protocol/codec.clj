(ns pds.protocol.codec
  (:require [pds.protocol.syntax :as syntax])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream DataInputStream DataOutputStream]
           [java.nio ByteBuffer CharBuffer]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Arrays Base64]))

(defrecord Link [cid])
(defn fail! [message] (throw (ex-info message {:type :invalid-data})))
(defn sha256 [^bytes value] (.digest (MessageDigest/getInstance "SHA-256") value))
(defn utf8 [^String value]
  (let [buffer (.encode (.newEncoder StandardCharsets/UTF_8) (CharBuffer/wrap value))
        out (byte-array (.remaining buffer))]
    (.get buffer out) out))
(defn text [^bytes value] (str (.decode (.newDecoder StandardCharsets/UTF_8) (ByteBuffer/wrap value))))
(def b32 "abcdefghijklmnopqrstuvwxyz234567")
(defn base32 [^bytes data]
  (let [out (StringBuilder.)]
    (loop [xs (seq data) acc 0 bits 0]
      (cond
        (>= bits 5) (let [remaining (- bits 5)]
                      (.append out (.charAt b32 (bit-and 31 (unsigned-bit-shift-right acc remaining))))
                      (recur xs (bit-and acc (dec (bit-shift-left 1 remaining))) remaining))
        xs (recur (next xs) (bit-or (bit-shift-left acc 8) (bit-and 255 (first xs))) (+ bits 8))
        (pos? bits) (do (.append out (.charAt b32 (bit-shift-left acc (- 5 bits)))) (str out))
        :else (str out)))))

(defn unbase32 [value]
  (when-not (and (string? value) (re-matches #"[a-z2-7]+" value)) (fail! "Invalid base32"))
  (let [out (ByteArrayOutputStream.)]
    (loop [xs (seq value) acc 0 bits 0]
      (cond
        (>= bits 8) (let [remaining (- bits 8)]
                      (.write out (bit-and 255 (unsigned-bit-shift-right acc remaining)))
                      (recur xs (bit-and acc (dec (bit-shift-left 1 remaining))) remaining))
        xs (recur (next xs) (bit-or (bit-shift-left acc 5) (.indexOf b32 (int (first xs)))) (+ bits 5))
        :else (let [result (.toByteArray out)]
                (when-not (= value (base32 result)) (fail! "Noncanonical base32"))
                result)))))

(defn cid-bytes [value]
  (when-not (and (string? value) (= 59 (count value)) (= \b (first value))) (fail! "Invalid CID"))
  (let [data (unbase32 (subs value 1))]
    (when-not (and (= 36 (alength data)) (= 1 (aget data 0))
                   (#{85 113} (aget data 1)) (= 18 (aget data 2)) (= 32 (aget data 3)))
      (fail! "Unsupported CID format"))
    data))
(defn cid
  ([data] (cid 113 data))
  ([codec data]
   (when-not (#{85 113} codec) (fail! "Unsupported CID codec"))
   (str "b" (base32 (byte-array (concat [1 codec 18 32] (sha256 data)))))))
(defn link [value] (cid-bytes value) (->Link value))

(defn from-json
  ([value] (from-json value 0))
  ([value depth]
   (when (> depth 64) (fail! "Data nesting exceeds 64 levels"))
   (cond
     (map? value)
     (do
       (when-not (every? string? (keys value)) (fail! "Map keys must be strings"))
       (cond
         (contains? value "$link")
         (do (when-not (= 1 (count value)) (fail! "Link must have one field")) (link (get value "$link")))
         (contains? value "$bytes")
         (do (when-not (and (= 1 (count value)) (string? (get value "$bytes"))) (fail! "Invalid bytes"))
             (.decode (Base64/getDecoder) ^String (get value "$bytes")))
         :else
         (do
           (when (contains? value "$type")
             (when-not (or (= "blob" (get value "$type")) (syntax/type-ref? (get value "$type")))
               (fail! "Invalid $type")))
           (let [result (into {} (map (fn [[k v]] [k (from-json v (inc depth))])) value)]
             (when (= "blob" (get result "$type"))
               (when-not (and (instance? Link (get result "ref"))
                              (= 85 (aget (cid-bytes (:cid (get result "ref"))) 1))
                              (string? (get result "mimeType")) (seq (get result "mimeType"))
                              (integer? (get result "size")) (<= 0 (get result "size")))
                 (fail! "Invalid blob reference")))
             result))))
     (vector? value) (mapv #(from-json % (inc depth)) value)
     (number? value) (if (and (<= Long/MIN_VALUE value Long/MAX_VALUE) (== value (long value)))
                       (long value) (fail! "Only signed 64-bit integers are allowed"))
     (or (nil? value) (boolean? value) (string? value)) value
     :else (fail! "Unsupported data type"))))

(defn to-json [value]
  (cond
    (instance? Link value) {"$link" (:cid value)}
    (bytes? value) {"$bytes" (.encodeToString (.withoutPadding (Base64/getEncoder)) value)}
    (map? value) (into {} (map (fn [[k v]] [k (to-json v)])) value)
    (vector? value) (mapv to-json value)
    :else value))

(defn- header! [^DataOutputStream out major n]
  (let [prefix (bit-shift-left major 5)]
    (cond
      (< n 24) (.writeByte out (bit-or prefix n))
      (<= n 255) (do (.writeByte out (+ prefix 24)) (.writeByte out n))
      (<= n 65535) (do (.writeByte out (+ prefix 25)) (.writeShort out n))
      (<= n 4294967295) (do (.writeByte out (+ prefix 26)) (.writeInt out (unchecked-int n)))
      :else (do (.writeByte out (+ prefix 27)) (.writeLong out n)))))

(defn- encode-value! [^DataOutputStream out value depth]
  (when (> depth 64) (fail! "Data nesting exceeds 64 levels"))
  (cond
    (nil? value) (.writeByte out 246)
    (boolean? value) (.writeByte out (if value 245 244))
    (integer? value) (do (when-not (<= Long/MIN_VALUE value Long/MAX_VALUE) (fail! "Integer out of range"))
                        (header! out (if (neg? value) 1 0) (if (neg? value) (bit-not (long value)) value)))
    (string? value) (let [data (utf8 value)] (header! out 3 (alength data)) (.write out data))
    (bytes? value) (do (header! out 2 (alength ^bytes value)) (.write out ^bytes value))
    (instance? Link value) (do (header! out 6 42) (header! out 2 37)
                              (.writeByte out 0) (.write out (cid-bytes (:cid value))))
    (vector? value) (do (header! out 4 (count value)) (doseq [v value] (encode-value! out v (inc depth))))
    (map? value)
    (do (when-not (every? string? (keys value)) (fail! "Map keys must be strings"))
        (header! out 5 (count value))
        (doseq [[k v] (sort (fn [[a] [b]]
                             (let [a (utf8 a) b (utf8 b) len (compare (alength a) (alength b))]
                               (if (zero? len) (Arrays/compareUnsigned a b) len))) value)]
          (encode-value! out k (inc depth)) (encode-value! out v (inc depth))))
    :else (fail! "Unsupported CBOR type")))

(defn encode [value]
  (let [buffer (ByteArrayOutputStream.) out (DataOutputStream. buffer)]
    (encode-value! out value 0)
    (.toByteArray buffer)))

(defn- argument [^DataInputStream in ai]
  (let [n (cond (< ai 24) ai (= ai 24) (.readUnsignedByte in) (= ai 25) (.readUnsignedShort in)
                (= ai 26) (Integer/toUnsignedLong (.readInt in)) (= ai 27) (.readLong in)
                :else (fail! "Indefinite or reserved CBOR encoding"))]
    (when (neg? n) (fail! "Integer out of range")) n))

(defn- read-bytes [^DataInputStream in n]
  (when (> n (.available in)) (fail! "Truncated CBOR"))
  (let [data (byte-array n)] (.readFully in data) data))

(declare decode-value)
(defn- decode-value [^DataInputStream in depth]
  (when (> depth 64) (fail! "Data nesting exceeds 64 levels"))
  (let [tag (.readUnsignedByte in) major (bit-shift-right tag 5) ai (bit-and tag 31)]
    (if (= major 7)
      (case ai 20 false 21 true 22 nil (fail! "Floats and CBOR simple values are not allowed"))
      (let [n (argument in ai) read-child #(decode-value in (inc depth))]
        (case major
          0 n
          1 (bit-not n)
          2 (read-bytes in n)
          3 (text (read-bytes in n))
          4 (do (when (> n (.available in)) (fail! "Truncated array")) (vec (repeatedly n read-child)))
          5 (do (when (> n (quot (.available in) 2)) (fail! "Truncated map"))
                (loop [i n result {}]
                  (if (zero? i) result
                      (let [k (read-child)]
                        (when (or (not (string? k)) (contains? result k)) (fail! "Invalid or duplicate map key"))
                        (recur (dec i) (assoc result k (read-child)))))))
          6 (do (when-not (= n 42) (fail! "Unsupported CBOR tag"))
                (let [data (read-child)]
                  (when-not (and (bytes? data) (= 37 (alength ^bytes data)) (zero? (aget ^bytes data 0)))
                    (fail! "Invalid CID link"))
                  (link (str "b" (base32 (Arrays/copyOfRange ^bytes data 1 37))))))
          (fail! "Unsupported CBOR major type"))))))

(defn decode
  ([data] (decode data (* 1024 1024)))
  ([^bytes data maximum]
  (when (> (alength data) maximum) (fail! "CBOR exceeds the decoding limit"))
  (let [in (DataInputStream. (ByteArrayInputStream. data)) value (decode-value in 0)]
    (when-not (and (zero? (.available in)) (Arrays/equals data (encode value)))
      (fail! "CBOR must use canonical encoding without trailing bytes"))
    value)))

(defn decode-pair
  "Decode the two concatenated canonical CBOR values of an event-stream frame."
  [^bytes data maximum]
  (when (> (alength data) maximum) (fail! "CBOR frame exceeds the decoding limit"))
  (let [in (DataInputStream. (ByteArrayInputStream. data))
        a (decode-value in 0) b (decode-value in 0)
        out (ByteArrayOutputStream.)]
    (.write out ^bytes (encode a)) (.write out ^bytes (encode b))
    (when-not (and (zero? (.available in)) (Arrays/equals data (.toByteArray out)))
      (fail! "Invalid canonical CBOR pair"))
    [a b]))
