(ns pds.blob-download-test
  (:require [clojure.test :refer [deftest is use-fixtures testing]]
            [pds.app :as app]
            [pds.blob-download :as download]
            [pds.blobs :as blobs]
            [pds.blob-storage-test :as storage]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.http-stream-test :as transport]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.repo-import-test :as imports]
            [pds.response-body :as body]
            [pds.server-api-test :as api])
  (:import [java.io ByteArrayInputStream IOException InputStream]
           [java.nio.channels FileChannel]
           [java.util Arrays Random]
           [java.util.concurrent Semaphore]))

(use-fixtures :each fixture/isolated-database)

(defn prepare [settings cid]
  (with-open [conn (db/connection fixture/*ds*)]
    (download/prepare! conn settings storage/did cid)))

(defn error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e [(:status (ex-data e)) (:error (ex-data e)) (.getMessage e)])))

(deftest postgres-download-is-bounded-and-independent-of-the-db-connection
  (storage/account!)
  (let [data (byte-array blobs/max-size) _ (.nextBytes (Random. 42) data)
        stored (db/transact! fixture/*ds* #(blobs/store! % {} storage/did data "application/octet-stream"))
        query db/query chunks (atom [])]
    (with-redefs [db/query (fn [conn sql & args]
                            (when (re-find #"SELECT content FROM blobs" sql)
                              (throw (ex-info "Whole bytea download is forbidden" {})))
                            (let [rows (apply query conn sql args)]
                              (when (contains? (first rows) :chunk)
                                (swap! chunks conj (alength ^bytes (:chunk (first rows)))))
                              rows))]
      (let [prepared (prepare {} (:cid stored))]
        ;; prepare has returned and its JDBC connection is already closed.
        (with-open [response (:body prepared)]
          (is (body/stream? response))
          (is (= blobs/max-size (:length response)))
          (is (Arrays/equals data (.readAllBytes ^InputStream (:input response)))))
        (is (true? @(:closed? (:body prepared))))))
    (is (> (count @chunks) 1))
    (is (every? #(<= % 65536) @chunks))
    (is (= blobs/max-size (reduce + @chunks)))))

(deftest failed-verification-closes-the-file-and-releases-capacity
  (storage/account!)
  (let [data (byte-array [1 2 3]) cid (codec/cid 85 data)
        result (atom data) reads (atom 0) closes (atom 0) channels (atom [])
        original @#'pds.blob-download/temporary-channel!
        store (reify blobs/ObjectStreaming
                (open-object! [_ bucket key]
                  (is (= ["test-bucket" "key"] [bucket key]))
                  (let [data @result]
                    (proxy [ByteArrayInputStream] [(or data (byte-array 0))]
                      (read [buffer start length]
                        (when (nil? data) (throw (IOException. "private provider details")))
                        (let [n (proxy-super read buffer start length)]
                          (when (pos? n) (swap! reads + n)) n))
                      (close [] (swap! closes inc))))))]
    (db/transact! fixture/*ds*
      #(db/execute! % "INSERT INTO blobs(did,cid,mime_type,size,storage_backend,object_bucket,object_key)
                       VALUES (?,?,'image/png',3,'s3','test-bucket','key')" storage/did cid))
    (with-redefs-fn {#'pds.blob-download/permits (Semaphore. 1)
                    #'pds.blob-download/temporary-channel!
                    (fn [] (let [c (original)] (swap! channels conj c) c))}
      (fn []
        (doseq [bad [(byte-array [1]) (byte-array [1 2 4]) (byte-array 1000000) nil]]
          (reset! result bad) (reset! reads 0)
          (is (= [503 "BlobUnavailable" "Blob storage is unavailable"]
                 (error #(prepare {:blob-store store} cid))))
          (is (<= @reads 4))
          (is (every? #(not (.isOpen ^FileChannel %)) @channels)))
        (is (= 4 @closes))
        (reset! result data)
        (with-open [response (:body (prepare {:blob-store store} cid))]
          (is (Arrays/equals data (.readAllBytes ^InputStream (:input response)))))
        (is (= 5 @closes))
        (is (every? #(not (.isOpen ^FileChannel %)) @channels))
        ;; Missing configuration and failed file allocation also free the slot.
        (is (= [503 "BlobUnavailable" "Blob storage is unavailable"] (error #(prepare {} cid))))
        (with-redefs-fn {#'pds.blob-download/temporary-channel! #(throw (IOException. "disk full"))}
          #(is (= [503 "BlobUnavailable" "Blob storage is unavailable"] (error (fn [] (prepare {:blob-store store} cid))))))
        (with-open [response (:body (prepare {:blob-store store} cid))]
          (is (= 3 (:length response))))))))

(deftest prepared-bodies-hold-capacity-until-closed
  (storage/account!)
  (let [stored (db/transact! fixture/*ds* #(blobs/store! % {} storage/did (byte-array 0) "image/png"))]
    (with-redefs-fn {#'pds.blob-download/permits (Semaphore. 1)}
      (fn []
        (let [response (:body (prepare {} (:cid stored)))]
          (try
            (is (= [503 "BlobDownloadBusy" "Blob download capacity is busy; retry later"]
                   (error #(prepare {} (:cid stored)))))
            (finally (.close response) (.close response))))
        (with-open [next (:body (prepare {} (:cid stored)))]
          (is (= -1 (.read ^InputStream (:input next))))
          (is (= "BlobDownloadBusy" (second (error #(prepare {} (:cid stored)))))))))))

(deftest postgres-corruption-and-takedowns-are-refused-before-body-publication
  (storage/account!)
  (let [data (byte-array [1 2 3]) stored (db/transact! fixture/*ds* #(blobs/store! % {} storage/did data "image/png"))
        cid (:cid stored)]
    (doseq [bad [(byte-array [1 2]) (byte-array [1 2 4]) (byte-array [1 2 3 4])]]
      ;; PostgreSQL itself enforces bytea length = metadata size. Keep that
      ;; invariant while corrupting the content addressed by the existing CID.
      (db/transact! fixture/*ds* #(db/execute! % "UPDATE blobs SET content = ?, size = ? WHERE cid = ?" bad (alength ^bytes bad) cid))
      (is (= [503 "BlobUnavailable" "Blob storage is unavailable"] (error #(prepare {} cid)))))
    (db/transact! fixture/*ds* #(db/execute! % "UPDATE blobs SET takedown_ref = 'test' WHERE cid = ?" cid))
    (is (= 400 (first (error #(prepare {} cid)))))))

(deftest public-download-disconnect-releases-the-prepared-body
  (let [settings (api/settings) account (imports/local! settings) did (:did account)
        data (byte-array blobs/max-size) _ (.nextBytes (Random. 17) data)
        stored (db/transact! fixture/*ds* #(blobs/store! % settings did data "application/octet-stream"))
        _ (db/transact! fixture/*ds*
            #(repo/apply-writes! % settings did
                [{:action :create :collection "com.example.file" :rkey "one"
                  :value {"$type" "com.example.file"
                          "file" {"$type" "blob" "ref" {"$link" (:cid stored)}
                                  "mimeType" "application/octet-stream" "size" blobs/max-size}}}] nil))
        prepared (promise) original download/prepare!]
    (with-redefs [download/prepare! (fn [& args] (let [result (apply original args)] (deliver prepared (:body result)) result))]
      (let [handler (app/handler settings fixture/*ds*)
            server (http/start! settings
                     (fn [request] (handler (assoc request :uri "/xrpc/com.atproto.sync.getBlob"
                                                   :query-string (str "did=" did "&cid=" (:cid stored))))))]
        (try
          (with-open [socket (transport/socket-request (:port server))]
            (is (.startsWith (transport/read-headers socket) "HTTP/1.1 200"))
            (.setSoLinger socket true 0))
          (let [response (deref prepared 3000 nil)]
            (is (some? response))
            (when response
              (loop [left 250]
                (when (and (pos? left) (not @(:closed? response)))
                  (Thread/sleep 20) (recur (dec left))))
              (is (true? @(:closed? response)))))
          (finally ((:stop! server))))))))
