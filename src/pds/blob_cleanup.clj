(ns pds.blob-cleanup
  (:require [pds.blobs :as blobs]
            [pds.db :as db])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn settings [env]
  (let [value (get env "PDS_BLOB_TEMP_TTL_SECONDS" "86400")]
    (when-not (and (string? value) (re-matches #"[0-9]{1,8}" value)
                   (<= 3600 (Long/parseLong value) 2592000))
      (throw (ex-info "PDS_BLOB_TEMP_TTL_SECONDS must be 3600 to 2592000 seconds" {})))
    {:blob-temp-ttl-seconds (Long/parseLong value)}))

(defn collect! [ds settings]
  (let [ttl (get settings :blob-temp-ttl-seconds 86400)]
    (db/transact! ds
      (fn [conn]
        ;; The account lock follows authenticated record/upload mutations. One
        ;; account and at most 50 blobs keep each sweep bounded across instances.
        (when-let [account (first (db/query conn
                                  "SELECT a.did FROM accounts a WHERE EXISTS
                                   (SELECT 1 FROM blobs b WHERE b.did = a.did AND b.uploaded_at < now() - (? * interval '1 second')
                                    AND NOT EXISTS (SELECT 1 FROM record_blob_refs r WHERE r.did = b.did AND r.cid = b.cid))
                                   ORDER BY a.did FOR UPDATE OF a SKIP LOCKED LIMIT 1" ttl))]
          (let [did (:did account)
                _ (db/query conn "SELECT did FROM repositories WHERE did = ? FOR UPDATE" did)
                rows (db/query conn "SELECT cid FROM blobs b WHERE did = ? AND uploaded_at < now() - (? * interval '1 second')
                                     AND NOT EXISTS (SELECT 1 FROM record_blob_refs r WHERE r.did = b.did AND r.cid = b.cid)
                                     ORDER BY uploaded_at, cid LIMIT 50" did ttl)]
            (reduce (fn [n {:keys [cid]}]
                      (blobs/lock! conn did cid)
                      (if (seq (db/query conn "SELECT 1 FROM blobs WHERE did = ? AND cid = ? AND uploaded_at < now() - (? * interval '1 second')" did cid ttl))
                        (do (blobs/remove-unreferenced! conn did [cid]) (inc n)) n)) 0 rows)))))))

(defn delete-one! [ds store]
  (db/transact! ds
    (fn [conn]
      (when-let [job (first (db/query conn "SELECT * FROM blob_delete_jobs WHERE status = 'pending' AND available_at <= now()
                                         ORDER BY available_at, id FOR UPDATE SKIP LOCKED LIMIT 1"))]
        ;; Never wait for a blob lock while holding the job row: a record
        ;; mutation may hold that blob lock while upserting this deletion job.
        (cond
          (and (:did job)
               (not (:locked (first (db/query conn "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0)) AS locked"
                                              (str "blob/" (:did job) "/" (:cid job))))))) :busy
          (seq (db/query conn "SELECT 1 FROM blobs WHERE storage_backend = 's3' AND object_bucket = ? AND object_key = ? LIMIT 1"
                         (:object_bucket job) (:object_key job)))
          (do (db/execute! conn "DELETE FROM blob_delete_jobs WHERE id = ?" (:id job)) :retained)
          :else
        ;; Keep the row and blob locks during the bounded request. A crash rolls
        ;; back and retries; S3 DELETE is idempotent even if the response was lost.
        (let [success? (try (blobs/delete-object! store (:object_bucket job) (:object_key job)) true
                            (catch Exception _ false))
              attempts (inc (:attempts job))]
          (if success?
            (do (db/execute! conn "DELETE FROM blob_delete_jobs WHERE id = ?" (:id job)) :deleted)
            (do (db/execute! conn "UPDATE blob_delete_jobs SET attempts = ?, status = ?, last_error = 'delete-failed',
                                   available_at = now() + (? * interval '1 second') WHERE id = ?"
                             attempts (if (>= attempts 10) "failed" "pending")
                             (long (min 3600 (* 5 (Math/pow 2 (dec attempts))))) (:id job))
                :retry))))))))

(defn start!
  ([ds store] (start! ds store {}))
  ([ds store settings]
    (let [executor (Executors/newSingleThreadScheduledExecutor)]
      (.scheduleWithFixedDelay executor
        ^Runnable (fn [] (try (collect! ds settings)
                             (catch Exception _ (binding [*out* *err*] (println "Temporary blob cleanup failed; retrying")))))
        60 60 TimeUnit/SECONDS)
      (when (satisfies? blobs/ObjectDeletion store)
      (.scheduleWithFixedDelay executor
        ^Runnable (fn [] (try (dotimes [_ 10] (delete-one! ds store))
                             (catch Exception _ (binding [*out* *err*] (println "Blob cleanup failed; retrying")))))
        0 1 TimeUnit/SECONDS))
      (fn [] (.shutdownNow executor) (.awaitTermination executor 35 TimeUnit/SECONDS)))))
