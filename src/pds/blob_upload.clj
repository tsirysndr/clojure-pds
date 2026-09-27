(ns pds.blob-upload
  (:require [clojure.string :as str]
            [pds.auth :as auth]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.oauth.permissions :as permissions]
            [pds.protocol.codec :as codec]
            [pds.tempfile :as tempfile])
  (:import [java.io ByteArrayInputStream InputStream IOException]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.security MessageDigest]
           [java.util.concurrent Semaphore]))

;; In addition to the separate download allowance. Includes staging and durable
;; publication, so slow clients and slow backends cannot create unlimited files.
(defonce ^:private permits (Semaphore. 16))

(defn- mime! [request]
  (let [mime (some-> (get-in request [:headers "content-type"]) (str/split #";") first str/lower-case)]
    (when-not (and mime (re-matches #"[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+" mime))
      (errors/invalid! "A valid Content-Type is required"))
    mime))

(defn- authorize! [conn settings request mime]
  (let [account (auth/authenticate! conn settings request {:allow-deactivated? true})]
    (permissions/blob! account mime)
    (:did account)))

(defn- too-large! [] (errors/raise! 413 "PayloadTooLarge" "Request body exceeds the limit"))

(defn- stage! [request ^FileChannel channel]
  (let [declared (when-let [value (get-in request [:headers "content-length"])]
                   (when-not (re-matches #"[0-9]{1,18}" value) (errors/invalid! "Invalid Content-Length"))
                   (Long/parseLong value))
        _ (when (and declared (> declared blobs/max-size)) (too-large!))
        ^InputStream input (or (:body request) (ByteArrayInputStream. (byte-array 0)))
        buffer (byte-array 65536) digest (MessageDigest/getInstance "SHA-256")
        deadline (+ (System/nanoTime) 30000000000)]
    (loop [size 0]
      (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
      (when (> (System/nanoTime) deadline) (errors/raise! 408 "RequestTimeout" "Blob upload timed out"))
      (let [n (try (.read input buffer 0 (int (min (alength buffer) (inc (- blobs/max-size size)))))
                   (catch IOException _ (errors/invalid! "Incomplete blob upload")))]
        (cond
          (= -1 n)
          (do (when (and declared (not= declared size)) (errors/invalid! "Incomplete blob upload"))
              {:size size :cid (str "b" (codec/base32 (byte-array (concat [1 85 18 32] (.digest digest)))))
               :open-input #(tempfile/input channel)})
          (> (+ size n) blobs/max-size) (too-large!)
          (zero? n) (errors/invalid! "Incomplete blob upload")
          :else (do (.update digest buffer 0 n)
                    (let [bytes (ByteBuffer/wrap buffer 0 n)]
                      (while (.hasRemaining bytes) (.write channel bytes)))
                    (recur (+ size n))))))))

(defn upload! [ds settings request]
  (let [mime (mime! request)
        did (db/transact! ds #(authorize! % settings request mime))]
    (when-not (.tryAcquire permits)
      (errors/raise! 503 "BlobUploadBusy" "Blob upload capacity is busy; retry later"))
    (try
      (with-open [channel (try (tempfile/open-channel!) (catch IOException _ (blobs/unavailable!)))]
        ;; No database connection or account lock while reading client bytes.
        (let [staged (stage! request channel)]
          (db/transact! ds
            (fn [conn]
              (when-not (= did (authorize! conn settings request mime))
                (errors/raise! 401 "InvalidToken" "Upload authorization changed"))
              (let [stored (blobs/store-stream! conn settings did staged mime)]
                {:blob {:$type "blob" :ref {:$link (:cid stored)}
                        :mimeType (:mime_type stored) :size (:size stored)}})))))
      (catch IOException _ (blobs/unavailable!))
      (finally (.release permits)))))
