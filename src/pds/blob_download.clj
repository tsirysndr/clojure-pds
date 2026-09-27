(ns pds.blob-download
  "Verify blobs with bounded JVM buffers before publishing an HTTP response."
  (:require [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.codec :as codec]
            [pds.tempfile :as tempfile]
            [pds.response-body :as body])
  (:import [java.io FilterInputStream InputStream]
           [java.nio ByteBuffer]
           [java.nio.channels Channels FileChannel]
           [java.security MessageDigest]
           [java.util.concurrent Semaphore]))

;; Includes prepared responses waiting for/streaming to slow clients. At the
;; current 5 MiB blob limit, this caps temporary disk usage at 80 MiB/process.
(defonce ^:private permits (Semaphore. 16))
(def chunk-size 65536)

(defn- temporary-channel! []
  (tempfile/open-channel!))

(defn- postgres-stream [conn did cid]
  (let [offset (atom 0)]
    (proxy [InputStream] []
      (read
        ([] (throw (UnsupportedOperationException. "Use bounded reads")))
        ([buffer start length]
         ;; pgjdbc materializes bytea even for getBinaryStream. Slice in SQL
         ;; so each JDBC result, not merely the HTTP write, stays bounded.
         (let [chunk (:chunk (first (db/query conn
                        "SELECT substring(content FROM ?::integer FOR ?::integer) AS chunk
                         FROM blobs WHERE did = ? AND cid = ? AND storage_backend = 'postgres'"
                        (inc @offset) (min length chunk-size) did cid)))]
           (when-not (bytes? chunk) (blobs/unavailable!))
           (let [n (alength ^bytes chunk)]
             (if (zero? n) -1
                 (do (System/arraycopy chunk 0 buffer start n) (swap! offset + n) n)))))))))

(defn- copy-verified! [^InputStream input ^FileChannel channel size cid]
  (let [buffer (byte-array chunk-size) digest (MessageDigest/getInstance "SHA-256")
        deadline (+ (System/nanoTime) 30000000000)]
    (loop [total 0]
      (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
      (when (> (System/nanoTime) deadline) (blobs/unavailable!))
      (let [n (.read input buffer 0 (int (min chunk-size (inc (- size total)))))]
        (cond
          (= -1 n)
          (when-not (and (= total size)
                         (= cid (str "b" (codec/base32 (byte-array (concat [1 85 18 32] (.digest digest)))))))
            (blobs/unavailable!))
          (or (zero? n) (> (+ total n) size)) (blobs/unavailable!)
          :else
          (do (.update digest buffer 0 n)
              (let [bytes (ByteBuffer/wrap buffer 0 n)]
                (while (.hasRemaining bytes) (.write channel bytes)))
              (recur (+ total n))))))))

(defn prepare!
  "Caller has authorized account visibility and blob references. Returns metadata
  plus an owned :body. All backend reads and CID verification finish here; sending
  the body needs no database connection or remote store. Close it if not returned."
  [conn settings did cid]
  (let [row (blobs/metadata conn did cid) size (:size row)]
    (when (or (nil? row) (some? (:takedown_ref row)))
      (errors/raise! 400 "BlobNotFound" "Blob was not found"))
    (when-not (and (integer? size) (<= 0 size blobs/max-size)) (blobs/unavailable!))
    (when-not (.tryAcquire permits)
      (errors/raise! 503 "BlobDownloadBusy" "Blob download capacity is busy; retry later"))
    (let [channel (atom nil) released? (atom false)
          release! #(when (compare-and-set! released? false true)
                      (try (when-let [^FileChannel c @channel] (.close c))
                           (finally (.release permits))))]
      (try
        (reset! channel (temporary-channel!))
        (with-open [input (case (:storage_backend row)
                           "postgres" (postgres-stream conn did cid)
                           "s3" (if-let [store (:blob-store settings)]
                                  (blobs/open-object! store (:object_bucket row) (:object_key row))
                                  (blobs/unavailable!))
                           (blobs/unavailable!))]
          (copy-verified! input @channel size cid))
        (.position ^FileChannel @channel 0)
        (let [input (proxy [FilterInputStream] [(Channels/newInputStream @channel)]
                      (close [] (release!)))]
          (assoc row :body (body/stream input size)))
        (catch Throwable error
          (try (release!) (catch Throwable _))
          (if (instance? Error error) (throw error) (blobs/unavailable!)))))))
