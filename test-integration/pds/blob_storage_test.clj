(ns pds.blob-storage-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.protocol.codec :as codec]))

(use-fixtures :each fixture/isolated-database)
(def did "did:web:storage.test")
(defn account! []
  (db/transact! fixture/*ds*
    #(db/execute! % "INSERT INTO accounts(did, handle, email, password_hash) VALUES (?, 'storage.test', 'storage@example.com', 'unused')" did)))

(deftest external-storage-failure-and-integrity
  (account!)
  (let [data (byte-array [1 2 3]) cid (codec/cid 85 data) calls (atom 0)
        result (atom data)
        store (reify blobs/ObjectStore
                (put-object! [_ _ _ _ _] (swap! calls inc) {:object-key "key" :object-bucket "test-bucket"})
                (get-object! [_ _ _ _] @result))
        settings {:blob-store store}]
    (is (thrown? Exception
                 (db/transact! fixture/*ds*
                   #(do (blobs/store! % settings did data "image/png")
                        (throw (ex-info "Simulate DB rollback after upload" {}))))))
    (with-open [conn (db/connection fixture/*ds*)] (is (nil? (blobs/metadata conn did cid))))
    (db/transact! fixture/*ds* #(blobs/store! % settings did data "image/png"))
    (is (= 2 @calls))
    (is (= "image/png" (:mime_type (db/transact! fixture/*ds* #(blobs/store! % settings did data "text/plain")))))
    (is (= 2 @calls) "A duplicate upload preserves the original MIME type without another PUT")
    (with-open [conn (db/connection fixture/*ds*)]
      (is (= (vec data) (vec (:content (blobs/read! conn settings did cid))))))
    (doseq [bad [(byte-array [1]) (byte-array [1 2 4]) (byte-array [1 2 3 4]) nil]]
      (reset! result bad)
      (with-open [conn (db/connection fixture/*ds*)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage is unavailable" (blobs/read! conn settings did cid)))))
    (with-open [conn (db/connection fixture/*ds*)]
      (is (thrown? clojure.lang.ExceptionInfo (blobs/read! conn {} did cid))))
    (let [broken (reify blobs/ObjectStore
                   (put-object! [_ _ _ _ _] (throw (ex-info "secret-provider-details" {})))
                   (get-object! [_ _ _ _] (throw (ex-info "secret-provider-details" {}))))]
      (try
        (db/transact! fixture/*ds* #(blobs/store! % {:blob-store broken} did (byte-array [4 5 6]) "text/plain"))
        (is false "Expected an upload failure")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Blob storage is unavailable" (.getMessage e)))
          (is (= 503 (:status (ex-data e)))))))
    (with-open [conn (db/connection fixture/*ds*)]
      (is (= 1 (:count (first (db/query conn "SELECT count(*) AS count FROM blobs"))))))))
