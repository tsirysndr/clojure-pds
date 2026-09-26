(ns pds.empty-blob-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.blob-refs-test :refer [blob]]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.plc-provision-test :refer [rows]]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.repo-import-test :as imports]
            [pds.s3 :as s3]
            [pds.s3-api-test :as s3-api]
            [pds.s3-test :as s3-test]
            [pds.server-api-test :as api])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.util UUID]
           [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.services.s3.model CreateBucketRequest]))

(use-fixtures :each fixture/isolated-database)

(defn round-trip! [settings]
  (let [account (imports/local! settings) did (:did account) token (:accessJwt account)
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)
        empty (byte-array 0) cid (codec/cid 85 empty)
        get-path (str "com.atproto.sync.getBlob?did=" did "&cid=" cid)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [uploaded (s3-api/upload client port token empty "application/octet-stream")
              blob (get-in uploaded [:body "blob"])
              record {"$type" "com.example.file" "file" blob}
              body {"repo" did "collection" "com.example.file" "rkey" "empty" "record" record}]
          (is (= 200 (:status uploaded)))
          (is (= 0 (get blob "size")))
          (is (= cid (get-in blob ["ref" "$link"])))
          (is (= 400 (:status (api/xrpc client port "GET" get-path nil nil))))
          (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.repo.createRecord" body token))))
          (is (= [cid] (get-in (api/xrpc client port "GET" (str "com.atproto.sync.listBlobs?did=" did) nil nil) [:body "cids"])))
          (let [request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/xrpc/" get-path))) .GET .build)
                response (.send client request (HttpResponse$BodyHandlers/ofByteArray))]
            (is (= 200 (.statusCode response)))
            (is (= 0 (alength ^bytes (.body response))))
            (is (= "0" (.orElse (.firstValue (.headers response) "Content-Length") "missing"))))
          ;; Empty bytes still have to match their declared metadata.
          (is (= 400 (:status (api/xrpc client port "POST" "com.atproto.repo.putRecord"
                                        (assoc-in body ["record" "file" "size"] 1) token))))
          (is (= blob (get-in (s3-api/upload client port token empty "text/plain") [:body "blob"])))
          (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.repo.deleteRecord" (dissoc body "record") token))))
          (is (= 400 (:status (api/xrpc client port "GET" get-path nil nil))))
          (is (= [] (rows "SELECT cid FROM blobs")))))
      (finally ((:stop! server))))))

(deftest postgres-empty-blob-lifecycle (round-trip! (api/settings)))

(when-let [endpoint (System/getenv "PDS_TEST_S3_ENDPOINT")]
  (deftest s3-empty-blob-lifecycle
    (let [bucket (str "pds-empty-" (UUID/randomUUID))
          config (s3/settings (assoc s3-test/config "PDS_S3_ENDPOINT" endpoint "PDS_S3_BUCKET" bucket))]
      (with-open [store (s3/open-store config)]
        (.createBucket ^S3Client (:client store) ^CreateBucketRequest (-> (CreateBucketRequest/builder) (.bucket bucket) .build))
        (round-trip! (assoc (api/settings) :blob-store store))))))

(deftest upgrade-recovers-empty-references-without-changing-the-repository
  (let [all db/migrations]
    (with-redefs [db/migrations (vec (take 22 all))]
      (fixture/isolated-database
        (fn []
          (let [settings (api/settings) did (:did (imports/local! settings))
                empty (byte-array 0) reference (blob empty)
                value {"$type" "com.example.file" "file" reference} data (codec/encode value)]
            ;; A pre-upgrade CAR import could contain this valid reference,
            ;; while the earlier reference index omitted size-zero metadata.
            (db/transact! fixture/*ds*
              (fn [conn]
                (let [cid (repo/block! conn data)]
                  (db/execute! conn "INSERT INTO records(did, collection, rkey, cid) VALUES (?, 'com.example.file', 'empty', ?)" did cid))
                (let [commit (repo/commit! conn settings (repo/state conn did))]
                  (repo/stamp-records! conn did (:rev commit)))))
            (let [before (rows "SELECT head, rev FROM repositories") record-before (rows "SELECT * FROM records")]
              (is (= [] (rows "SELECT * FROM record_blob_refs")))
              (with-redefs [db/migrations all] (is (db/migrate! fixture/*ds*)))
              (is (= before (rows "SELECT head, rev FROM repositories")))
              (is (= record-before (rows "SELECT * FROM records")))
              (is (= [{:cid (codec/cid 85 empty)}] (rows "SELECT cid FROM record_blob_refs")))
              (db/transact! fixture/*ds* #(blobs/store! % settings did empty "image/png"))
              (with-open [conn (db/connection fixture/*ds*)]
                (is (= [] (vec (:content (blobs/read! conn settings did (codec/cid 85 empty))))))))))))))
