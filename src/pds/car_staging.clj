(ns pds.car-staging
  "Disk-backed, hash-checked CAR payloads with an in-memory CID/offset index."
  (:require [pds.protocol.car :as car]
            [pds.protocol.codec :as codec])
  (:import [java.io IOException]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]))

(defn stage!
  "Visit a bounded CAR into a caller-owned empty private file. Returns roots and
  a block loader valid only while that channel remains open. Duplicate payloads
  occupy disk once but every wire occurrence counts toward the input limit.
  Retains CID/offset/length metadata, never a map of block byte arrays."
  [^FileChannel channel input maximum]
  (when-not (zero? (.size channel)) (throw (IllegalArgumentException. "CAR staging file must be empty")))
  (.position channel 0)
  (let [index (atom {})
        result (car/visit! input maximum
                 (fn [cid data]
                   (when-not (contains? @index cid)
                     (let [offset (.position channel) bytes (ByteBuffer/wrap data)]
                       (while (.hasRemaining bytes) (.write channel bytes))
                       (swap! index assoc cid [offset (alength ^bytes data)])))))
        offsets @index]
    (assoc result :load-block
      (fn [cid]
        (when-let [[offset length] (get offsets cid)]
          (let [data (byte-array length) buffer (ByteBuffer/wrap data)]
            (loop [position offset]
              (when (.hasRemaining buffer)
                (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
                (let [n (.read channel buffer (long position))]
                  (when (<= n 0) (throw (IOException. "Incomplete staged CAR block")))
                  (recur (+ position n)))))
            ;; Recheck bytes read from disk, including after verification and
            ;; before publication; the index never grants ownership by itself.
            (when-not (= cid (codec/cid (aget (codec/cid-bytes cid) 1) data))
              (throw (IOException. "Corrupt staged CAR block")))
            data))))))
