(ns pds.blobs
  (:require [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.codec :as codec]))

(def max-size (* 5 1024 1024))

(defprotocol ObjectStore
  (put-object! [store did cid content mime-type]
    "Persist content; return {:object-key string :object-bucket string}.")
  (get-object! [store bucket key size]
    "Read at most size+1 bytes. The caller verifies length and content hash."))

(defn unavailable! [] (errors/raise! 503 "BlobUnavailable" "Blob storage is unavailable"))

(defn metadata [conn did cid]
  (first (db/query conn "SELECT did, cid, mime_type, size, storage_backend, object_key, object_bucket
                        FROM blobs WHERE did = ? AND cid = ?" did cid)))

(defn store!
  "Caller owns the transaction. Publish metadata only after object persistence.
  A failed DB commit may leave an unreferenced object; never delete it here since
  a concurrent retry may reference the same content-addressed key."
  [conn settings did content mime-type]
  (let [size (alength ^bytes content) cid (codec/cid 85 content)]
    (when-not (<= 1 size max-size) (errors/invalid! "Blob size is outside the allowed range"))
    ;; Serialize duplicates across PDS processes; the first MIME type wins.
    (db/query conn "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))" (str "blob/" did "/" cid))
    (or (metadata conn did cid)
        (do
          (if-let [store (:blob-store settings)]
            (let [{:keys [object-key object-bucket]}
                  (try (put-object! store did cid content mime-type)
                       (catch Exception _ (unavailable!)))]
              (db/execute! conn "INSERT INTO blobs(did, cid, mime_type, size, storage_backend, object_key, object_bucket)
                                VALUES (?, ?, ?, ?, 's3', ?, ?)"
                           did cid mime-type size object-key object-bucket))
            (db/execute! conn "INSERT INTO blobs(did, cid, mime_type, size, content) VALUES (?, ?, ?, ?, ?)"
                         did cid mime-type size content))
          (metadata conn did cid)))))

(defn read! [conn settings did cid]
  (let [row (or (metadata conn did cid) (errors/raise! 400 "BlobNotFound" "Blob was not found"))
        content (case (:storage_backend row)
                  "postgres" (:content (first (db/query conn "SELECT content FROM blobs WHERE did = ? AND cid = ?" did cid)))
                  "s3" (if-let [store (:blob-store settings)]
                         (try (get-object! store (:object_bucket row) (:object_key row) (:size row))
                              (catch Exception _ (unavailable!)))
                         (unavailable!))
                  (unavailable!))]
    ;; Do not serve missing, truncated, oversized or corrupted external content.
    (when-not (and (bytes? content) (= (:size row) (alength ^bytes content)) (= cid (codec/cid 85 content)))
      (unavailable!))
    (assoc row :content content)))
