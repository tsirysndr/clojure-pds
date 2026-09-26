(ns pds.s3-api-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.s3 :as s3]
            [pds.s3-test :as unit]
            [pds.server-api-test :as api])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.util UUID]
           [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.services.s3.model CreateBucketRequest]))

(use-fixtures :each fixture/isolated-database)

(defn upload [client port token bytes mime]
  (let [request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/xrpc/com.atproto.repo.uploadBlob")))
                    (.header "Authorization" (str "Bearer " token)) (.header "Content-Type" mime)
                    (.POST (HttpRequest$BodyPublishers/ofByteArray bytes)) .build)
        response (.send ^HttpClient client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (json/read-str (.body response))}))

;; The ordinary integration suite does not need an external service. This test
;; is registered only by scripts/test-s3.py, which starts a fresh local emulator.
(when-let [endpoint (System/getenv "PDS_TEST_S3_ENDPOINT")]
  (deftest s3-http-round-trip-and-missing-object
    (let [bucket (str "pds-test-" (UUID/randomUUID))
          config (s3/settings (assoc unit/config "PDS_S3_ENDPOINT" endpoint "PDS_S3_BUCKET" bucket))
          settings (api/settings)
          alice (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "test-password"})
          bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})
          data (byte-array [0 1 2 -1 -128 42])]
      (with-open [store (s3/open-store config) client (HttpClient/newHttpClient)]
        (.createBucket ^S3Client (:client store) ^CreateBucketRequest (-> (CreateBucketRequest/builder) (.bucket bucket) .build))
        (let [settings (assoc settings :blob-store store)
              server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)]
          (try
            (let [response (upload client port (:accessJwt alice) data "image/png")
                  blob (get-in response [:body "blob"]) cid (get-in blob ["ref" "$link"])
                  get-path (str "com.atproto.sync.getBlob?did=" (:did alice) "&cid=" cid)
                  download (api/xrpc client port "GET" get-path nil nil)]
              (is (= 200 (:status response) (:status download)))
              (is (= (vec data) (vec (:raw download))))
              (is (= blob (get-in (upload client port (:accessJwt alice) data "text/plain") [:body "blob"])))
              (is (= 400 (:status (api/xrpc client port "GET" (str "com.atproto.sync.getBlob?did=" (:did bob) "&cid=" cid) nil nil))))
              (is (= [] (get-in (api/xrpc client port "GET" (str "com.atproto.sync.listBlobs?did=" (:did alice)) nil nil) [:body "cids"])))
              (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.repo.createRecord"
                                          {"repo" (:did alice) "collection" "app.bsky.actor.profile" "rkey" "self"
                                           "record" {"$type" "app.bsky.actor.profile" "avatar" blob}}
                                          (:accessJwt alice)))))
              (is (= [cid] (get-in (api/xrpc client port "GET" (str "com.atproto.sync.listBlobs?did=" (:did alice)) nil nil) [:body "cids"])))
              (with-open [conn (db/connection fixture/*ds*)]
                (let [row (first (db/query conn "SELECT * FROM blobs WHERE did = ? AND cid = ?" (:did alice) cid))]
                  (is (= "s3" (:storage_backend row))) (is (nil? (:content row))) (is (= 6 (:size row)))
                  ;; Reopen the S3 client to verify reads use persisted locators.
                  (with-open [reopened (s3/open-store (assoc config :prefix "changed-prefix"))]
                    (is (= (vec data) (vec (:content (blobs/read! conn {:blob-store reopened} (:did alice) cid))))))
                  (blobs/delete-object! store bucket (:object_key row))))
              (let [missing (api/xrpc client port "GET" get-path nil nil)]
                (is (= 503 (:status missing)))
                (is (= "BlobUnavailable" (get-in missing [:body "error"]))))
              ;; Missing bucket must fail the upload without storing metadata or
              ;; silently falling back to PostgreSQL bytes.
              (with-open [missing-store (s3/open-store (assoc config :bucket "nonexistent-bucket"))]
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage is unavailable"
                                     (db/transact! fixture/*ds*
                                       #(blobs/store! % {:blob-store missing-store} (:did alice) (byte-array [9 8]) "image/png")))))
              (with-open [conn (db/connection fixture/*ds*)]
                (is (= 1 (:count (first (db/query conn "SELECT count(*) AS count FROM blobs")))))))
            (finally ((:stop! server)))))))))
