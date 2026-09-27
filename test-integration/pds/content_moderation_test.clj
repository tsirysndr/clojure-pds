(ns pds.content-moderation-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.invites-test :as admin]
            [pds.moderation :as moderation]
            [pds.protocol.car :as car]
            [pds.protocol.repository :as repository]
            [pds.repo-import-test :as imports]
            [pds.s3 :as s3]
            [pds.s3-api-test :as blob-api]
            [pds.s3-test :as s3-test]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]
           [java.util UUID]
           [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.services.s3.model CreateBucketRequest]))

(use-fixtures :each fixture/isolated-database)

(defn with-service [extra f]
  (let [settings (merge (api/settings) {:admin-password admin/admin-password} extra)
        alice (accounts/create! fixture/*ds* settings (admin/signup "alice" nil))
        bob (accounts/create! fixture/*ds* settings (admin/signup "bob" nil))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (f {:settings settings :alice alice :bob bob :client client :port (:port server)}))
      (finally ((:stop! server))))))

(defn call [{:keys [client port]} method endpoint body token]
  (api/xrpc client port method endpoint body token))
(defn admin-call [{:keys [client port]} method endpoint body]
  (admin/admin-call client port method endpoint body))
(defn status [env query]
  (admin-call env "GET" (str "com.atproto.admin.getSubjectStatus?" query) nil))
(defn take-down [env subject attr]
  (admin-call env "POST" "com.atproto.admin.updateSubjectStatus" {"subject" subject "takedown" attr}))
(defn body [account rkey value]
  {"repo" (:did account) "collection" "com.example.record" "rkey" rkey
   "record" (merge {"$type" "com.example.record"} value)})
(defn write [env account rkey value]
  (call env "POST" "com.atproto.repo.putRecord" (body account rkey value) (:accessJwt account)))
(defn get-record [env account rkey]
  (call env "GET" (str "com.atproto.repo.getRecord?repo=" (:did account) "&collection=com.example.record&rkey=" rkey) nil nil))
(defn rows [sql & params]
  (with-open [c (db/connection fixture/*ds*)] (apply db/query c sql params)))
(defn public-state []
  [(rows "SELECT did, head, rev FROM repositories ORDER BY did")
   (rows "SELECT seq, did, event_type FROM repo_events ORDER BY seq")
   (rows "SELECT did, collection, rkey, cid FROM record_blob_refs ORDER BY did, collection, rkey, cid")])

(deftest record-takedowns-filter-indexed-reads-and-preserve-signed-data
  (with-service {}
    (fn [{:keys [alice bob] :as env}]
      (doseq [rkey ["a" "b" "c"]] (is (= 200 (:status (write env alice rkey {"text" rkey})))))
      (let [record (:body (get-record env alice "b"))
            subject (assoc (select-keys record ["uri" "cid"]) "$type" "com.atproto.repo.strongRef")
            query (str "uri=" (get subject "uri"))
            backup (imports/export (:did alice))]
        (is (= 200 (:status (write env bob "b" {"text" "b"}))))
        (is (= (get subject "cid") (get-in (get-record env bob "b") [:body "cid"])))
        (let [state (public-state)]
          (is (= 200 (:status (take-down env subject {"applied" true "ref" "case-record"}))))
          (is (= state (public-state))))
        (is (= {"applied" true "ref" "case-record"} (get-in (status env query) [:body "takedown"])))
        (is (= "RecordNotFound" (get-in (get-record env alice "b") [:body "error"])))
        (is (= 200 (:status (get-record env bob "b"))))
        (let [base (str "com.atproto.repo.listRecords?repo=" (:did alice) "&collection=com.example.record&limit=1")
              first-page (call env "GET" base nil nil)
              next-page (call env "GET" (str base "&cursor=" (get-in first-page [:body "cursor"])) nil nil)
              reverse-page (call env "GET" (str base "&reverse=true") nil nil)]
          (is (= "a" (get-in first-page [:body "records" 0 "value" "text"])))
          (is (= "c" (get-in next-page [:body "records" 0 "value" "text"])))
          (is (nil? (get-in next-page [:body "cursor"])))
          (is (= "c" (get-in reverse-page [:body "records" 0 "value" "text"]))))
        (is (= (vec backup) (vec (imports/export (:did alice)))))
        (is (= 3 (count (:paths (repository/verify-car backup (:did alice) (imports/local-key (:settings env) (:did alice)))))))
        (doseq [path [(str "com.atproto.sync.getRecord?did=" (:did alice) "&collection=com.example.record&rkey=b")
                      (str "com.atproto.sync.getBlocks?did=" (:did alice) "&cids=" (get subject "cid"))]]
          (let [response (call env "GET" path nil nil)]
            (is (= 200 (:status response)))
            (is (contains? (:blocks (car/decode (:raw response))) (get subject "cid")))))
        ;; URI-scoped moderation survives updates, even when CID changes.
        (is (= 200 (:status (write env alice "b" {"text" "updated"}))))
        (is (= 400 (:status (get-record env alice "b"))))
        (is (not= (get subject "cid") (get-in (status env query) [:body "subject" "cid"])))
        (is (= 200 (:status (take-down env subject {"applied" false}))))
        (is (= "updated" (get-in (get-record env alice "b") [:body "value" "text"])))
        (is (= 200 (:status (take-down env subject {"applied" true "ref" "case-record"}))))
        ;; A verified replacement import must not clear local takedowns.
        (is (= 200 (:status ((app/handler (:settings env) fixture/*ds*) (imports/import-request (:accessJwt alice) backup)))))
        (is (= {"applied" true "ref" "case-record"} (get-in (status env query) [:body "takedown"])))
        (is (= 400 (:status (get-record env alice "b"))))
        (is (= 200 (:status (take-down env subject {"applied" false}))))
        (is (= "b" (get-in (get-record env alice "b") [:body "value" "text"])))
        ;; Deletion ends that indexed record's lifetime, as in the reference PDS.
        (is (= 200 (:status (take-down env subject {"applied" true}))))
        (is (= 200 (:status (call env "POST" "com.atproto.repo.deleteRecord" (dissoc (body alice "b" {}) "record") (:accessJwt alice)))))
        (is (= "NotFound" (get-in (status env query) [:body "error"])))
        (is (= 200 (:status (write env alice "b" {"text" "new"}))))
        (is (= {"applied" false} (get-in (status env query) [:body "takedown"])))))))

(defn blob-round-trip [extra]
  (with-service extra
    (fn [{:keys [alice bob client port] :as env}]
      (let [data (byte-array [0 -1 42 3])
            upload #(blob-api/upload client port (:accessJwt %) data "application/octet-stream")
            blob (get-in (upload alice) [:body "blob"])
            cid (get-in blob ["ref" "$link"])
            subject {"$type" "com.atproto.admin.defs#repoBlobRef" "did" (:did alice) "cid" cid}
            query (str "did=" (:did alice) "&blob=" cid)
            download #(call env "GET" (str "com.atproto.sync.getBlob?did=" (:did %) "&cid=" cid) nil nil)]
        (is (= 200 (:status (upload bob))))
        (doseq [account [alice bob]] (is (= 200 (:status (write env account "blob" {"file" blob})))))
        (let [state (public-state)
              locator (rows "SELECT object_key, object_bucket, size FROM blobs WHERE did = ? AND cid = ?" (:did alice) cid)]
          (is (= 200 (:status (take-down env subject {"applied" true}))))
          (is (= state (public-state)))
          (is (= locator (rows "SELECT object_key, object_bucket, size FROM blobs WHERE did = ? AND cid = ?" (:did alice) cid)))
          (is (empty? (rows "SELECT * FROM blob_delete_jobs"))))
        (is (true? (get-in (status env query) [:body "takedown" "applied"])))
        (is (string? (get-in (status env query) [:body "takedown" "ref"])))
        (is (= "BlobNotFound" (get-in (download alice) [:body "error"])))
        (is (= (vec data) (vec (:raw (download bob)))))
        (is (= 400 (:status (upload alice))))
        (is (= 400 (:status (write env alice "blocked" {"file" blob}))))
        (is (= 400 (:status (write env alice "legacy" {"file" {"cid" cid "mimeType" "application/octet-stream"}}))))
        (is (= [cid] (get-in (call env "GET" (str "com.atproto.sync.listBlobs?did=" (:did alice)) nil nil) [:body "cids"])))
        (is (= [] (get-in (call env "GET" "com.atproto.repo.listMissingBlobs" nil (:accessJwt alice)) [:body "blobs"])))
        (let [backup (imports/export (:did alice))]
          (is (= 200 (:status ((app/handler (:settings env) fixture/*ds*)
                              (imports/import-request (:accessJwt alice) backup)))))
          (is (= "BlobNotFound" (get-in (download alice) [:body "error"]))))
        (let [state (public-state)]
          (is (= 400 (:status (call env "POST" "com.atproto.repo.applyWrites"
                                    {"repo" (:did alice) "writes"
                                     [{"$type" "com.atproto.repo.applyWrites#create" "collection" "com.example.record" "rkey" "valid"
                                       "value" {"$type" "com.example.record"}}
                                      {"$type" "com.atproto.repo.applyWrites#create" "collection" "com.example.record" "rkey" "blocked"
                                       "value" {"$type" "com.example.record" "file" blob}}]} (:accessJwt alice)))))
          (is (= state (public-state)))
          (is (= 400 (:status (get-record env alice "valid")))))
        (is (= 200 (:status (take-down env subject {"applied" false}))))
        (is (= (vec data) (vec (:raw (download alice)))))
        (is (= 200 (:status (upload alice))))
        (is (= 200 (:status (write env alice "legacy" {"file" {"cid" cid "mimeType" "application/octet-stream"}}))))
        (is (= 200 (:status (write env alice "restored" {"file" blob}))))))))

(deftest postgres-blob-takedown-and-restoration (blob-round-trip {}))

(when-let [endpoint (System/getenv "PDS_TEST_S3_ENDPOINT")]
  (deftest s3-blob-takedown-and-restoration
    (let [bucket (str "pds-moderation-" (UUID/randomUUID))
          config (s3/settings (assoc s3-test/config "PDS_S3_ENDPOINT" endpoint "PDS_S3_BUCKET" bucket))]
      (with-open [store (s3/open-store config)]
        (.createBucket ^S3Client (:client store) ^CreateBucketRequest (-> (CreateBucketRequest/builder) (.bucket bucket) .build))
        (blob-round-trip {:blob-store store})))))

(deftest subject-validation-and-admin-authorization
  (with-service {}
    (fn [{:keys [alice] :as env}]
      (let [record (:body (write env alice "one" {}))
            subject (assoc (select-keys record ["uri" "cid"]) "$type" "com.atproto.repo.strongRef")
            query (str "uri=" (get subject "uri"))]
        (is (= 401 (:status (call env "POST" "com.atproto.admin.updateSubjectStatus" {"subject" subject "takedown" {"applied" true}} (:accessJwt alice)))))
        (is (= 401 (:status (call env "GET" (str "com.atproto.admin.getSubjectStatus?" query) nil nil))))
        (doseq [invalid [(assoc subject "uri" "at://alice.example.com/com.example.record/one")
                         (assoc subject "uri" (str "at://" (:did alice)))
                         (assoc subject "cid" "bad")
                         (assoc subject "$type" "com.example.unknown")]]
          (is (= 400 (:status (take-down env invalid {"applied" true})))))
        (is (= 400 (:status (take-down env subject {"applied" "true"}))))
        (is (= {"applied" false} (get-in (status env query) [:body "takedown"])))
        (is (= "NotFound" (get-in (status env (str "uri=at://" (:did alice) "/com.example.record/missing")) [:body "error"])))
        (is (= 400 (:status (status env (str "blob=" (get subject "cid"))))))
        ;; Empty references are legal and must still mean an applied takedown.
        (is (= 200 (:status (take-down env subject {"applied" true "ref" ""}))))
        (is (= {"applied" true "ref" ""} (get-in (status env query) [:body "takedown"])))
        (is (= 400 (:status (get-record env alice "one"))))))))

(deftest moderation-serializes-with-authenticated-blob-writes
  (with-service {}
    (fn [{:keys [alice client port] :as env}]
      (let [blob (get-in (blob-api/upload client port (:accessJwt alice) (byte-array [1]) "image/png") [:body "blob"])
            subject {"$type" "com.atproto.admin.defs#repoBlobRef" "did" (:did alice) "cid" (get-in blob ["ref" "$link"])}
            locked (promise) release (promise)
            moderator (future (db/transact! fixture/*ds*
                                (fn [c] (moderation/update-status! c {"subject" subject "takedown" {"applied" true}})
                                  (deliver locked true) (deref release 5000 nil))))]
        (try
          (is (= true (deref locked 5000 :timeout)))
          (let [writer (future (write env alice "raced" {"file" blob}))]
            (is (= :pending (deref writer 100 :pending)))
            (deliver release true)
            (is (not= :timeout (deref moderator 5000 :timeout)))
            (is (= 400 (:status (deref writer 5000 {}))))
            (is (= 400 (:status (get-record env alice "raced")))))
          (finally (deliver release true) (deref moderator 5000 nil)))))))
