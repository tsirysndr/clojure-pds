(ns pds.blob-cleanup-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.blob-cleanup :as cleanup]
            [pds.blob-refs-test :refer [blob]]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.plc-provision-test :refer [rows scalar]]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.repo-import-test :as imports]
            [pds.s3 :as s3]
            [pds.s3-test :as s3-test]
            [pds.server-api-test :as api])
  (:import [java.util UUID]
           [java.util.concurrent CountDownLatch TimeUnit]
           [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.services.s3.model CreateBucketRequest]))
(use-fixtures :each fixture/isolated-database)

(defn put! [settings did data]
  (db/transact! fixture/*ds* #(blobs/store! % settings did data "image/png")))
(defn write [data key]
  {:action :put :collection "com.example.file" :rkey key :value {"$type" "com.example.file" "file" (codec/to-json (blob data))}})
(defn delete [key] {:action :delete :collection "com.example.file" :rkey key})
(defn writes! [settings did writes]
  (db/transact! fixture/*ds* #(repo/apply-writes! % settings did writes nil)))

(deftest temporary-gc-preserves-references-renewed-uploads-and-the-grace-period
  (let [settings (api/settings) account (imports/local! settings) did (:did account)
        data (mapv #(byte-array [%]) [1 2 3 4]) cids (mapv #(codec/cid 85 %) data)]
    (doseq [bytes data] (put! settings did bytes))
    (writes! settings did [(write (nth data 2) "one")])
    (db/transact! fixture/*ds* #(db/execute! % "UPDATE blobs SET uploaded_at = now() - interval '2 days'"))
    (put! settings did (nth data 1))
    (put! settings did (nth data 3))
    (is (= 1 (cleanup/collect! fixture/*ds* settings)))
    (is (= (set (rest cids)) (set (map :cid (rows "SELECT cid FROM blobs")))))
    (is (nil? (cleanup/collect! fixture/*ds* settings)))
    ;; Removing one reference and adding another in a batch must not delete
    ;; shared content between the two writes.
    (writes! settings did [(delete "one") (write (nth data 2) "two")])
    (with-open [conn (db/connection fixture/*ds*)]
      (is (= (vec (nth data 2)) (vec (:content (blobs/read! conn settings did (nth cids 2)))))))
    (writes! settings did [(delete "two")])
    (is (= #{(nth cids 1) (nth cids 3)} (set (map :cid (rows "SELECT cid FROM blobs")))))))

(deftest temporary-sweeps-are-bounded
  (let [settings (api/settings) did (:did (imports/local! settings))]
    (doseq [i (range 52)] (put! settings did (byte-array [i])))
    (db/transact! fixture/*ds* #(db/execute! % "UPDATE blobs SET uploaded_at = now() - interval '2 days'"))
    (is (= 50 (cleanup/collect! fixture/*ds* settings)))
    (is (= 2 (cleanup/collect! fixture/*ds* settings)))
    (is (nil? (cleanup/collect! fixture/*ds* settings)))))

(deftest temporary-collection-serializes-with-internal-repository-writes
  (let [settings (api/settings) did (:did (imports/local! settings)) data (byte-array [42])
        entered (promise) collecting (promise) release (promise)
        validate repo/record-value! query db/query]
    (put! settings did data)
    (db/transact! fixture/*ds* #(db/execute! % "UPDATE blobs SET uploaded_at = now() - interval '2 days'"))
    (with-redefs [db/query (fn [conn sql & args]
                            (when (= sql "SELECT did FROM repositories WHERE did = ? FOR UPDATE")
                              (deliver collecting true))
                            (apply query conn sql args))
                  repo/record-value! (fn [& args]
                                     (let [value (apply validate args)]
                                       (deliver entered true)
                                       (when (= :timeout (deref release 5000 :timeout)) (throw (ex-info "Barrier timeout" {})))
                                       value))]
      (let [writer (future (writes! settings did [(write data "one")]))]
        (try
          (is (= true (deref entered 5000 :timeout)))
          (let [gc (future (cleanup/collect! fixture/*ds* settings))]
            (is (= true (deref collecting 5000 :timeout)))
            (is (= :waiting (deref gc 100 :waiting)))
            (deliver release true)
            (is (map? (deref writer 5000 :timeout)))
            (is (= 0 (deref gc 5000 :timeout))))
          (finally (deliver release true)))))
    (is (= 1 (scalar "SELECT count(*) AS n FROM blobs")))
    (is (= 1 (scalar "SELECT count(*) AS n FROM record_blob_refs")))))

(deftest durable-deletion-is-safe-before-during-and-after-reupload
  (let [base (api/settings) did (:did (imports/local! base)) data (byte-array [1 2 3]) cid (codec/cid 85 data)
        object (atom nil) deletes (atom 0) puts (atom 0)
        entered (CountDownLatch. 1) release (CountDownLatch. 1) block-delete? (atom false)
        store (reify blobs/ObjectStore
                (put-object! [_ _ _ bytes _] (swap! puts inc) (reset! object bytes) {:object-bucket "bucket" :object-key "key"})
                (get-object! [_ _ _ _] @object)
                blobs/ObjectDeletion
                (delete-object! [_ _ _]
                  (when @block-delete?
                    (.countDown entered)
                    (when-not (.await release 10 TimeUnit/SECONDS) (throw (ex-info "Timeout" {}))))
                  (swap! deletes inc) (reset! object nil)))
        settings (assoc base :blob-store store)]
    (put! settings did data)
    (writes! settings did [(write data "one")])
    (is (thrown? Exception (db/transact! fixture/*ds* (fn [conn]
                                                     (repo/apply-writes! conn settings did [(delete "one")] nil)
                                                     (throw (ex-info "Rollback" {}))))))
    (is (= 1 (scalar "SELECT count(*) AS n FROM blobs")))
    (is (= 0 (scalar "SELECT count(*) AS n FROM blob_delete_jobs")))
    (writes! settings did [(delete "one")])
    (is (= 0 (scalar "SELECT count(*) AS n FROM blobs")))
    (put! settings did data)
    (is (= :retained (cleanup/delete-one! fixture/*ds* store)))
    (is (= 0 @deletes))
    (db/transact! fixture/*ds* #(blobs/remove-unreferenced! % did [cid]))
    ;; Avoid the job-row/blob-lock inversion when an old deletion job exists.
    (db/transact! fixture/*ds*
      (fn [conn]
        (blobs/lock! conn did cid)
        (is (= :busy (deref (future (cleanup/delete-one! fixture/*ds* store)) 5000 :timeout)))
        (blobs/remove-unreferenced! conn did [cid])))
    (reset! block-delete? true)
    (let [worker (future (cleanup/delete-one! fixture/*ds* store))]
      (try
        (is (.await entered 5 TimeUnit/SECONDS))
        (let [upload (future (put! settings did data)) before @puts]
          (is (= :waiting (deref upload 100 :waiting)))
          (is (= before @puts) "PUT must wait until the remote DELETE completes")
          (.countDown release)
          (is (= :deleted (deref worker 5000 :timeout)))
          (is (= cid (:cid (deref upload 5000 {})))))
        (finally (.countDown release))))
    (with-open [conn (db/connection fixture/*ds*)]
      (is (= (vec data) (vec (:content (blobs/read! conn settings did cid))))))
    (is (= 1 @deletes))))

(when-let [endpoint (System/getenv "PDS_TEST_S3_ENDPOINT")]
  (deftest real-s3-temporary-deletion-and-reupload
    (let [base (api/settings) did (:did (imports/local! base)) data (byte-array [8 9])
          bucket (str "pds-gc-" (UUID/randomUUID))
          config (s3/settings (assoc s3-test/config "PDS_S3_ENDPOINT" endpoint "PDS_S3_BUCKET" bucket))]
      (with-open [store (s3/open-store config)]
        (.createBucket ^S3Client (:client store) ^CreateBucketRequest (-> (CreateBucketRequest/builder) (.bucket bucket) .build))
        (let [settings (assoc base :blob-store store) stored (put! settings did data)]
          (db/transact! fixture/*ds* #(db/execute! % "UPDATE blobs SET uploaded_at = now() - interval '2 days'"))
          (is (= 1 (cleanup/collect! fixture/*ds* settings)))
          (is (= :deleted (cleanup/delete-one! fixture/*ds* store)))
          (is (thrown? Exception (blobs/get-object! store bucket (:object_key stored) 2)))
          (let [old (put! settings did data)]
            (writes! settings did [(write data "one")])
            (writes! settings did [(delete "one")])
            (let [uncertain (reify blobs/ObjectDeletion (delete-object! [_ _ _] (throw (ex-info "Response timeout" {}))))]
              (is (= :retry (cleanup/delete-one! fixture/*ds* uncertain))))
            (let [new (put! settings did data)]
              (is (not= (:object_key old) (:object_key new)))
              ;; The earlier request completes after the client has timed out
              ;; and after a retry has stored new bytes at its own locator.
              (blobs/delete-object! store bucket (:object_key old))
              (is (= (vec data) (vec (blobs/get-object! store bucket (:object_key new) 2))))
              (db/transact! fixture/*ds* #(db/execute! % "UPDATE blob_delete_jobs SET available_at = now()"))
              (is (= :deleted (cleanup/delete-one! fixture/*ds* store)))
              (is (= (vec data) (vec (blobs/get-object! store bucket (:object_key new) 2)))))))))))
