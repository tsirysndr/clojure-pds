(ns pds.s3-test
  (:require [clojure.test :refer [deftest is]]
            [pds.blobs :as blobs]
            [pds.http :as http]
            [pds.protocol.codec :as codec]
            [pds.s3 :as s3]))

(def config {"PDS_BLOB_BACKEND" "s3" "PDS_S3_BUCKET" "pds-test-blobs"
             "PDS_S3_ACCESS_KEY_ID" "test-access" "PDS_S3_SECRET_ACCESS_KEY" "test-secret"
             "PDS_S3_FORCE_PATH_STYLE" "true"})

(deftest s3-settings
  (is (nil? (s3/settings {})))
  (is (= "us-east-1" (:region (s3/settings config))))
  (is (= "auto" (:region (s3/settings (assoc config "PDS_S3_REGION" "auto")))) )
  (is (nil? (:access-key (s3/settings (dissoc config "PDS_S3_ACCESS_KEY_ID" "PDS_S3_SECRET_ACCESS_KEY")))))
  (doseq [[key value] [["PDS_BLOB_BACKEND" "disk"] ["PDS_S3_BUCKET" ""] ["PDS_S3_BUCKET" "bad/bucket"]
                       ["PDS_S3_REGION" ""] ["PDS_S3_ENDPOINT" "http://remote.example.com"]
                       ["PDS_S3_ENDPOINT" "https://key:secret@example.com"]
                       ["PDS_S3_ENDPOINT" "https://example.com/bucket"]
                       ["PDS_S3_ENDPOINT" "https://example.com/?token=private"]
                       ["PDS_S3_SECRET_ACCESS_KEY" ""] ["PDS_S3_FORCE_PATH_STYLE" "yes"]
                       ["PDS_S3_PREFIX" "../blobs"] ["PDS_S3_PREFIX" "/blobs"]]]
    (is (thrown? clojure.lang.ExceptionInfo (s3/settings (assoc config key value))) key))
  (is (thrown? clojure.lang.ExceptionInfo (s3/settings (dissoc config "PDS_S3_SECRET_ACCESS_KEY")))))

(deftest sdk-signs-and-bounds-requests
  (let [requests (atom []) data (byte-array [1 2 -1 3]) cid (codec/cid 85 data)
        server (http/start!
                {:host "127.0.0.1" :port 0}
                (fn [r]
                  (swap! requests conj (select-keys r [:uri :request-method :headers]))
                  (if (= :put (:request-method r))
                    {:status 200 :headers {} :body ""}
                    {:status 200 :headers {"Content-Type" "application/octet-stream"} :body data})))
        settings (s3/settings (assoc config "PDS_S3_ENDPOINT" (str "http://127.0.0.1:" (:port server))
                                           "PDS_S3_SESSION_TOKEN" "temporary-session" "PDS_S3_PREFIX" "test/blobs"))]
    (try
      (with-open [store (s3/open-store settings)]
        (let [{:keys [object-key object-bucket]} (blobs/put-object! store "did:web:alice.test" cid data "image/png")]
          (is (= "pds-test-blobs" object-bucket))
          (is (= (vec data) (vec (blobs/get-object! store object-bucket object-key (alength data)))))
          (is (= 2 (alength (blobs/get-object! store object-bucket object-key 1))) "An oversized response is bounded at expected size plus one")
          (is (= 1 (alength (blobs/get-object! store object-bucket object-key 0))) "An allegedly empty object still reads one byte to detect corruption")
          (is (not= object-key (s3/object-key "test/blobs" "did:web:bob.test" cid)))
          (is (= [:put :get :get :get] (mapv :request-method @requests)))
          (is (every? #(= (str "/pds-test-blobs/" object-key) (:uri %)) @requests))
          (is (every? #(re-find #"^AWS4-HMAC-SHA256 Credential=test-access/" (get-in % [:headers "authorization"])) @requests))
          (is (every? #(= "temporary-session" (get-in % [:headers "x-amz-security-token"])) @requests))))
      (finally ((:stop! server))))))
