(ns pds.blob-migration-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.blob-refs :as refs]
            [pds.blob-refs-test :refer [blob]]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.plc-provision-test :refer [rows scalar]]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.repo-import-test :as imports]
            [pds.s3 :as s3]
            [pds.s3-api-test :refer [upload]]
            [pds.s3-test :as s3-test]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]
           [java.util UUID]
           [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.services.s3.model CreateBucketRequest]))
(use-fixtures :each fixture/isolated-database)

(defn transfer! [settings]
  (let [{:keys [account source]} (imports/prepare! settings) did (:did account)
        a (byte-array [1 2 3]) b (byte-array [4 5 6]) a-blob (blob a) b-blob (blob b)
        a-cid (:cid (get a-blob "ref")) b-cid (:cid (get b-blob "ref"))
        values {"com.example.record/a" {"nested" [a-blob a-blob] "legacy" {"cid" a-cid "mimeType" "image/png"}}
                "com.example.record/b" {"nested" [a-blob b-blob]}
                "com.example.record/c" {"legacy" {"cid" b-cid "mimeType" "image/png"}}}
        f (imports/repo-car did (:key source) values)
        handler (app/handler settings fixture/*ds*) server (http/start! settings handler)]
    (try
      (is (= 200 (:status (handler (imports/import-request (:accessJwt account) (:car f))))))
      (is (= 4 (scalar "SELECT count(*) AS n FROM record_blob_refs")))
      (with-open [client (HttpClient/newHttpClient)]
        (let [missing #(api/xrpc client (:port server) "GET" (str "com.atproto.repo.listMissingBlobs" %) nil (:accessJwt account))
              first-page (:body (missing "?limit=1"))
              second-page (:body (missing (str "?limit=1&cursor=" (get first-page "cursor"))))
              expected {a-cid (str "at://" did "/com.example.record/a") b-cid (str "at://" did "/com.example.record/b")}
              all (concat (get first-page "blobs") (get second-page "blobs"))]
          (is (= (sort [a-cid b-cid]) (map #(get % "cid") all)))
          (is (= expected (into {} (map (juxt #(get % "cid") #(get % "recordUri"))) all)))
          (is (nil? (get second-page "cursor")))
          (is (= 400 (:status (missing "?cursor=invalid"))))
          (is (= 400 (:status (missing "?limit=0"))))
          (is (= 401 (:status (api/xrpc client (:port server) "GET" "com.atproto.repo.listMissingBlobs" nil nil))))
          (is (= 200 (:status (upload client (:port server) (:accessJwt account) a "image/png"))))
          (is (= [b-cid] (mapv #(get % "cid") (get-in (missing "") [:body "blobs"]))))
          ;; Metadata, byte storage and visibility agree across duplicate retries.
          (is (= 200 (:status (upload client (:port server) (:accessJwt account) a "text/plain"))))
          (is (= 200 (:status (upload client (:port server) (:accessJwt account) b "image/png"))))
          (is (= [] (get-in (missing "") [:body "blobs"])))
          (with-open [conn (db/connection fixture/*ds*)]
            (is (= (vec a) (vec (:content (blobs/read! conn settings did a-cid)))))
            (is (= (vec b) (vec (:content (blobs/read! conn settings did b-cid))))))
          (is (= 400 (:status (api/xrpc client (:port server) "GET" (str "com.atproto.sync.getBlob?did=" did "&cid=" a-cid) nil nil))))
          (is (= 0 (scalar "SELECT count(*) AS n FROM repo_events")))
          ;; Replacing the imported repository removes references atomically;
          ;; separately transferred blob bytes are retained for later GC.
          (is (= 200 (:status (handler (imports/import-request (:accessJwt account) (:car (imports/repo-car did (:key source) {})))))))
          (is (= 0 (scalar "SELECT count(*) AS n FROM record_blob_refs")))
          (is (= 2 (scalar "SELECT count(*) AS n FROM blobs")))))
      (finally ((:stop! server))))))

(deftest postgres-inactive-blob-transfer (transfer! (api/settings)))

(when-let [endpoint (System/getenv "PDS_TEST_S3_ENDPOINT")]
  (deftest s3-inactive-blob-transfer
    (let [bucket (str "pds-import-" (UUID/randomUUID))
          config (s3/settings (assoc s3-test/config "PDS_S3_ENDPOINT" endpoint "PDS_S3_BUCKET" bucket))]
      (with-open [store (s3/open-store config)]
        (.createBucket ^S3Client (:client store) ^CreateBucketRequest (-> (CreateBucketRequest/builder) (.bucket bucket) .build))
        (transfer! (assoc (api/settings) :blob-store store))))))

(deftest record-reference-maintenance-is-transactional-and-account-scoped
  (let [settings (api/settings) account (imports/local! settings) did (:did account)
        bytes (byte-array [42]) native (blob bytes) cid (:cid (get native "ref"))
        write {:action :put :collection "com.example.file" :rkey "one"
               :value {"$type" "com.example.file" "file" (codec/to-json native)}}]
    (db/transact! fixture/*ds* #(blobs/store! % settings did bytes "image/png"))
    (db/transact! fixture/*ds* #(repo/apply-writes! % settings did [write] nil))
    (is (= [{:cid cid}] (rows "SELECT cid FROM record_blob_refs")))
    (with-open [conn (db/connection fixture/*ds*)]
      (is (empty? (refs/missing conn did nil 10)))
      (is (empty? (refs/missing conn "did:web:unrelated.example.net" nil 10))))
    (is (thrown? Exception
                 (db/transact! fixture/*ds* (fn [conn]
                                             (repo/apply-writes! conn settings did [(assoc write :action :delete)] nil)
                                             (throw (ex-info "Rollback" {}))))))
    (is (= 1 (scalar "SELECT count(*) AS n FROM record_blob_refs")))
    (db/transact! fixture/*ds* #(repo/apply-writes! % settings did [(assoc write :value {"$type" "com.example.file"})] nil))
    (is (= 0 (scalar "SELECT count(*) AS n FROM record_blob_refs")))
    (db/transact! fixture/*ds* #(repo/apply-writes! % settings did [write] nil))
    (db/transact! fixture/*ds* #(repo/apply-writes! % settings did [(assoc write :action :delete)] nil))
    (is (= 0 (scalar "SELECT count(*) AS n FROM record_blob_refs")))))

(deftest upgrade-backfills-existing-records-without-rewriting-them
  (let [all db/migrations]
    (with-redefs [db/migrations (vec (take 19 all))]
      (fixture/isolated-database
        (fn []
          (let [settings (api/settings) account (imports/local! settings) did (:did account)
                value {"$type" "com.example.file" "file" (blob (byte-array [9]))} data (codec/encode value)]
            (db/transact! fixture/*ds* (fn [conn]
                                       (let [cid (repo/block! conn data)]
                                         (db/execute! conn "INSERT INTO records(did, collection, rkey, cid) VALUES (?, 'com.example.file', 'one', ?)" did cid))
                                       (repo/commit! conn settings (repo/state conn did))))
            (let [before (rows "SELECT head, rev FROM repositories")]
              (with-redefs [db/migrations all] (is (db/migrate! fixture/*ds*)))
              (is (= before (rows "SELECT head, rev FROM repositories")))
              (is (= [{:cid (:cid (get-in value ["file" "ref"]))}] (rows "SELECT cid FROM record_blob_refs")))
              (with-open [conn (db/connection fixture/*ds*)]
                (is (= 1 (count (refs/missing conn did nil 10))))))))))))
