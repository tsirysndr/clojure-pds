(ns pds.blob-upload-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.blob-upload :as upload]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.oauth-resource-test :as oauth]
            [pds.oauth-tokens-test :as tokens]
            [pds.protocol.codec :as codec]
            [pds.repo-import-test :as imports]
            [pds.server-api-test :as api]
            [pds.tempfile :as tempfile])
  (:import [java.io ByteArrayInputStream IOException InputStream]
           [java.net Socket URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.channels FileChannel]
           [java.util Arrays Random]
           [java.util.function Supplier]
           [java.util.concurrent Semaphore]))

(use-fixtures :each fixture/isolated-database)
(def path "/xrpc/com.atproto.repo.uploadBlob")
(defn request [account input]
  {:uri path :request-method :post
   :headers {"authorization" (str "Bearer " (:accessJwt account)) "content-type" "image/png"}
   :body input})
(defn rows [] (with-open [conn (db/connection fixture/*ds*)] (db/query conn "SELECT * FROM blobs")))
(defn tracked [data f]
  (proxy [ByteArrayInputStream] [data]
    (read [buffer offset length] (f length) (proxy-super read buffer offset length))))

(deftest maximum-upload-uses-bounded-reads-and-publishes-exact-bytea
  (let [settings (api/settings) account (imports/local! settings)
        data (byte-array blobs/max-size) _ (.nextBytes (Random. 13) data)
        reads (atom []) handler (app/handler settings fixture/*ds*)]
    (with-redefs [blobs/store! (fn [& _] (throw (ex-info "Buffered upload forbidden" {})))]
      (let [response (handler (request account (tracked data #(swap! reads conj %))))
            output (json/read-str (:body response)) row (first (rows))]
        (is (= 200 (:status response)))
        (is (= (codec/cid 85 data) (get-in output ["blob" "ref" "$link"])))
        (is (= blobs/max-size (:size row)))
        (is (Arrays/equals data ^bytes (:content row)))
        (is (> (count @reads) 1))
        (is (every? #(<= % 65536) @reads))
        (is (= 1 (count (rows))))))))

(deftest invalid-or-incomplete-input-never-publishes-and-releases-capacity
  (let [settings (api/settings) account (imports/local! settings) handler (app/handler settings fixture/*ds*)
        channels (atom []) original tempfile/open-channel!]
    (with-redefs-fn {#'pds.blob-upload/permits (Semaphore. 1)
                    #'tempfile/open-channel! #(let [c (original)] (swap! channels conj c) c)}
      (fn []
        (doseq [[input headers expected]
                [[(ByteArrayInputStream. (byte-array (inc blobs/max-size))) {} 413]
                 [(ByteArrayInputStream. (byte-array 2)) {"content-length" "3"} 400]
                 [(ByteArrayInputStream. (byte-array 3)) {"content-length" "2"} 400]
                 [(ByteArrayInputStream. (byte-array 0)) {"content-length" (str (inc blobs/max-size))} 413]
                 [(ByteArrayInputStream. (byte-array 0)) {"content-length" "wrong"} 400]
                 [(proxy [InputStream] []
                    (read
                      ([] (throw (IOException. "private source failure")))
                      ([buffer offset length] (throw (IOException. "private source failure"))))) {} 400]]]
          (let [response (handler (update (request account input) :headers merge headers))]
            (is (= expected (:status response)))
            (is (not (.contains ^String (:body response) "private source failure")))
            (is (empty? (rows)))
            (is (every? #(not (.isOpen ^FileChannel %)) @channels))))
        (is (= 200 (:status (handler (request account (ByteArrayInputStream. (byte-array 0)))))))
        (is (every? #(not (.isOpen ^FileChannel %)) @channels))))))

(deftest upload-reading-holds-no-account-lock-and-rechecks-session-revocation
  (let [settings (api/settings) account (imports/local! settings) handler (app/handler settings fixture/*ds*)
        changed? (atom false) ds fixture/*ds*
        input (tracked (byte-array [1 2 3])
                (fn [_]
                  (when (compare-and-set! changed? false true)
                    ;; A separate transaction can obtain the account lock while
                    ;; this request is reading. A lock timeout catches regressions.
                    (db/transact! ds
                      (fn [conn]
                        (db/execute! conn "SET LOCAL lock_timeout = '1s'")
                        (db/query conn "SELECT did FROM accounts WHERE did = ? FOR UPDATE" (:did account))
                        (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ?" (:did account)))))))]
    (is (= 401 (:status (handler (request account input)))))
    (is @changed?)
    (is (empty? (rows)))))

(deftest oauth-upload-rechecks-grant-without-replaying-the-dpop-proof
  (let [env (tokens/env) settings (oauth/settings env) handler (app/handler settings fixture/*ds*)
        issued (oauth/mint env "atproto transition:generic") changed? (atom false) ds fixture/*ds*
        input (tracked (byte-array [1 2 3])
                (fn [_]
                  (when (compare-and-set! changed? false true)
                    (db/transact! ds #(db/execute! % "UPDATE oauth_sessions SET revoked_at = now()")))))]
    (is (= 401 (:status (oauth/call handler (-> (oauth/request env issued :post path)
                                               (assoc-in [:headers "content-type"] "image/png")
                                               (assoc :body input))))))
    (is (empty? (rows)))
    (let [fresh (oauth/mint env "atproto transition:generic")
          request (-> (oauth/request env fresh :post path)
                      (assoc-in [:headers "content-type"] "image/png")
                      (assoc :body (ByteArrayInputStream. (byte-array [4 5 6]))))]
      (is (= 200 (:status (oauth/call handler request))))
      (is (= "invalid_dpop_proof" (get-in (oauth/call handler request) [:json "error"])))
      (is (= 1 (count (rows)))))))

(deftest interrupted-http-upload-releases-temporary-file-without-metadata
  (let [settings (api/settings) account (imports/local! settings) opened (promise)
        original tempfile/open-channel! server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-redefs [tempfile/open-channel! #(let [channel (original)] (deliver opened channel) channel)]
        (with-open [socket (Socket. "127.0.0.1" (:port server))]
          (.write (.getOutputStream socket)
                  (.getBytes (str "POST " path " HTTP/1.1\r\nHost: localhost\r\nContent-Type: image/png\r\nAuthorization: Bearer "
                                  (:accessJwt account) "\r\nContent-Length: 1000000\r\n\r\npartial") "US-ASCII"))
          (.flush (.getOutputStream socket))
          (is (instance? FileChannel (deref opened 3000 nil)))
          (.setSoLinger socket true 0))
        (let [channel (deref opened 3000 nil)]
          (when channel
            (loop [left 250]
              (when (and (pos? left) (.isOpen ^FileChannel channel))
                (Thread/sleep 20) (recur (dec left))))
            (is (not (.isOpen ^FileChannel channel)))))
        (is (empty? (rows))))
      (finally ((:stop! server))))))

(deftest capacity-and-storage-failures-release-upload-slots
  (let [settings (api/settings) account (imports/local! settings) handler (app/handler settings fixture/*ds*)
        nested (atom nil) once? (atom false)
        input (tracked (byte-array [1 2 3])
                (fn [_]
                  (when (compare-and-set! once? false true)
                    (reset! nested (handler (request account (ByteArrayInputStream. (byte-array 0))))))))]
    (with-redefs-fn {#'pds.blob-upload/permits (Semaphore. 1)}
      (fn []
        (is (= 200 (:status (handler (request account input)))))
        (is (= 503 (:status @nested)))
        (is (= "BlobUploadBusy" (get (json/read-str (:body @nested)) "error")))
        (with-redefs [tempfile/open-channel! #(throw (IOException. "private temporary path"))]
          (let [failed (handler (request account (ByteArrayInputStream. (byte-array [4]))))]
            (is (= 503 (:status failed)))
            (is (= "BlobUnavailable" (get (json/read-str (:body failed)) "error")))
            (is (not (.contains ^String (:body failed) "private temporary path")))))
        (let [broken (reify blobs/ObjectUpload
                       (put-stream! [_ _ _ _ _ _] (throw (IOException. "private storage credentials"))))
              handler (app/handler (assoc settings :blob-store broken) fixture/*ds*)
              failed (handler (request account (ByteArrayInputStream. (byte-array [5]))))]
          (is (= 503 (:status failed)))
          (is (= "BlobUnavailable" (get (json/read-str (:body failed)) "error")))
          (is (not (.contains ^String (:body failed) "private storage credentials"))))
        (is (= 1 (count (rows))))
        (is (= 200 (:status (handler (request account (ByteArrayInputStream. (byte-array 0)))))))))))

(deftest chunked-http-upload-enforces-the-same-size-and-integrity-boundaries
  (let [settings (api/settings) account (imports/local! settings)
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (doseq [[size expected] [[100000 200] [(inc blobs/max-size) 413]]]
          (let [data (byte-array size) _ (.nextBytes (Random. 31) data)
                publisher (HttpRequest$BodyPublishers/ofInputStream
                            (reify Supplier (get [_] (ByteArrayInputStream. data))))
                request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" (:port server) path)))
                            (.header "Authorization" (str "Bearer " (:accessJwt account)))
                            (.header "Content-Type" "application/octet-stream") (.POST publisher) .build)
                response (.send client request (HttpResponse$BodyHandlers/ofString))]
            (is (= -1 (.contentLength publisher)) "The client sends chunked HTTP without a known length")
            (is (= expected (.statusCode response)))
            (when (= 200 expected)
              (is (= (codec/cid 85 data) (get-in (json/read-str (.body response)) ["blob" "ref" "$link"])))
              (is (Arrays/equals data ^bytes (:content (first (rows)))))))))
      (is (= 1 (count (rows))))
      (finally ((:stop! server))))))
