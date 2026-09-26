(ns pds.blob-cleanup
  (:require [pds.blobs :as blobs]
            [pds.db :as db])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn delete-one! [ds store]
  (db/transact! ds
    (fn [conn]
      (when-let [job (first (db/query conn "SELECT * FROM blob_delete_jobs WHERE status = 'pending' AND available_at <= now()
                                         ORDER BY available_at, id FOR UPDATE SKIP LOCKED LIMIT 1"))]
        ;; Keep the row locked during the bounded remote request. A crash rolls
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
                :retry)))))))

(defn start! [ds store]
  (if-not (satisfies? blobs/ObjectDeletion store)
    (fn [])
    (let [executor (Executors/newSingleThreadScheduledExecutor)]
      (.scheduleWithFixedDelay executor
        ^Runnable (fn [] (try (dotimes [_ 10] (delete-one! ds store))
                             (catch Exception _ (binding [*out* *err*] (println "Blob cleanup failed; retrying")))))
        0 1 TimeUnit/SECONDS)
      (fn [] (.shutdownNow executor) (.awaitTermination executor 35 TimeUnit/SECONDS)))))
