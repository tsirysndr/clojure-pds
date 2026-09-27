(ns pds.blobs
  (:require [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.codec :as codec]))

(def max-size (* 5 1024 1024))

(defprotocol ObjectStore
  (put-object! [store did cid content mime-type]
    "Persist content; return {:object-key string :object-bucket string}.
    Use a fresh immutable locator per PUT so delayed deletes cannot affect retries.")
  (get-object! [store bucket key size]
    "Read at most size+1 bytes. The caller verifies length and content hash."))

(defprotocol ObjectDeletion
  (delete-object! [store bucket key] "Idempotently remove an object."))

(defn unavailable! [] (errors/raise! 503 "BlobUnavailable" "Blob storage is unavailable"))

(defn lock! [conn did cid]
  (db/query conn "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))" (str "blob/" did "/" cid)))

(defn referenced? [conn did cid]
  (boolean (seq (db/query conn "SELECT 1 FROM record_blob_refs WHERE did = ? AND cid = ? LIMIT 1" did cid))))

(defn remove-unreferenced!
  "Caller serializes record mutations with the account/repository. Shared blob
  locks serialize remote deletion and uploads; S3 deletion is queued atomically."
  [conn did cids]
  (doseq [cid (sort (set cids))]
    (lock! conn did cid)
    (when-not (referenced? conn did cid)
      (db/execute! conn "INSERT INTO blob_delete_jobs(did, cid, object_bucket, object_key)
                        SELECT did, cid, object_bucket, object_key FROM blobs
                        WHERE did = ? AND cid = ? AND storage_backend = 's3'
                        ON CONFLICT (object_bucket, object_key) DO UPDATE
                        SET did = excluded.did, cid = excluded.cid, status = 'pending', attempts = 0, available_at = now(), last_error = NULL"
                   did cid)
      (db/execute! conn "DELETE FROM blobs WHERE did = ? AND cid = ?" did cid))))

(defn metadata [conn did cid]
  (first (db/query conn "SELECT did, cid, mime_type, size, storage_backend, object_key, object_bucket, takedown_ref
                        FROM blobs WHERE did = ? AND cid = ?" did cid)))

(defn store!
  "Caller owns the transaction. Publish metadata only after object persistence.
  A failed DB commit may leave an unreferenced object; never delete it here since
  uncertain remote outcomes require separately tracked orphan reconciliation."
  [conn settings did content mime-type]
  (let [size (alength ^bytes content) cid (codec/cid 85 content)]
    (when-not (<= 0 size max-size) (errors/invalid! "Blob size is outside the allowed range"))
    ;; Serialize duplicates across PDS processes; the first MIME type wins.
    (lock! conn did cid)
    (or (when-let [existing (metadata conn did cid)]
          (when (some? (:takedown_ref existing))
            (errors/invalid! "Blob has been taken down and cannot be uploaded"))
          (when-not (referenced? conn did cid)
            (db/execute! conn "UPDATE blobs SET uploaded_at = now() WHERE did = ? AND cid = ?" did cid))
          existing)
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
  (let [row (metadata conn did cid)
        _ (when (or (nil? row) (some? (:takedown_ref row)))
            (errors/raise! 400 "BlobNotFound" "Blob was not found"))
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
