(ns pds.repo-import-stream-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.car-staging :as staging]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.mst :as mst]
            [pds.protocol.repository :as repository]
            [pds.repo :as repo]
            [pds.repo-import :as importer]
            [pds.repo-import-test :as imports]
            [pds.request :as request]
            [pds.server-api-test :as api]
            [pds.tempfile :as tempfile])
  (:import [java.io ByteArrayInputStream IOException InputStream]
           [java.net Socket URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.channels FileChannel]
           [java.time Duration]
           [java.util.concurrent Semaphore]
           [java.util.function Supplier]))

(use-fixtures :each fixture/isolated-database)
(def path "/xrpc/com.atproto.repo.importRepo")
(defn env []
  (let [settings (api/settings) account (imports/local! settings) did (:did account)
        key (imports/local-key settings did)]
    {:settings settings :account account :did did :key key
     :handler (app/handler settings fixture/*ds*)}))
(defn req [account bytes] (imports/import-request (:accessJwt account) bytes))
(defn tracked [data f]
  (let [source (ByteArrayInputStream. data)]
    (proxy [InputStream] []
      (available [] (throw (AssertionError. "No available() for network input")))
      (read ([] (f 1) (.read source))
            ([buffer offset length] (f length) (.read source buffer offset (min 4093 length)))))))
(defn closed? [channels] (every? #(not (.isOpen ^FileChannel %)) channels))

(deftest multi-megabyte-import-avoids-buffered-paths-and-preserves-the-verified-root
  (let [{:keys [account did key handler]} (env)
        values (into {} (for [i (range 12)] [(str "com.example.record/" i) {"n" i "text" (apply str (repeat 200000 "x"))}]))
        f (imports/repo-car did key values) reads (atom [])
        channels (atom []) original tempfile/open-channel!]
    (with-redefs [tempfile/open-channel! #(let [c (original)] (swap! channels conj c) c)
                  request/body-bytes (fn [& _] (throw (AssertionError. "Whole-body buffering forbidden")))
                  car/decode (fn [& _] (throw (AssertionError. "Whole-CAR buffering forbidden")))
                  repository/verify-car (fn [& _] (throw (AssertionError. "Buffered verification forbidden")))
                  mst/build (fn [& _] (throw (AssertionError. "Buffered tree construction forbidden")))
                  repo/commit! (fn [& _] (throw (AssertionError. "Import must use the verified root")))]
      (is (= 200 (:status (handler (assoc (req account (:car f)) :body (tracked (:car f) #(swap! reads conj %))))))))
    (is (closed? @channels))
    (is (= 1 (count @channels)))
    (is (every? #(<= % 65536) @reads))
    (let [verified (repository/verify-car (imports/export did) did key)]
      (is (= (:root f) (:root verified)))
      (is (= values (into {} (map (fn [{:keys [collection rkey cid]}]
                                    [(str collection "/" rkey) (get (:records verified) cid)])) (:paths verified))))
      (is (pos? (compare (:rev verified) (get-in f [:commit "rev"])))))))

(deftest invalid-input-and-storage-failures-close-files-and-return-import-capacity
  (let [{:keys [account did key handler]} (env) f (imports/repo-car did key {"com.example.record/a" {}})
        bytes (:car f) before (imports/state) channels (atom []) original tempfile/open-channel!]
    (with-redefs-fn {#'pds.repo-import/permits (Semaphore. 1)
                    #'tempfile/open-channel! #(let [c (original)] (swap! channels conj c) c)}
      (fn []
        (doseq [[data headers expected]
                [[bytes {"content-length" (str (inc (alength bytes)))} 400]
                 [bytes {"content-length" (str (dec (alength bytes)))} 400]
                 [bytes {"content-length" "invalid"} 400]
                 [bytes {"content-length" (str (inc importer/max-size))} 413]
                 [(byte-array (concat (seq bytes) [-128])) {} 400]
                 [(byte-array [0]) {} 400]]]
          (let [response (handler (update (req account data) :headers merge headers))]
            (is (= expected (:status response)))
            (is (closed? @channels))
            (is (= before (imports/state)))))
        (let [broken (proxy [InputStream] []
                       (read ([] (throw (IOException. "private network details")))
                             ([buffer offset length] (throw (IOException. "private network details")))))
              response (handler (assoc (req account bytes) :body broken))]
          (is (= 400 (:status response)))
          (is (not (.contains ^String (:body response) "private network details"))))
        (with-redefs [tempfile/open-channel! #(throw (IOException. "private disk path"))]
          (let [response (handler (req account bytes))]
            (is (= 503 (:status response)))
            (is (= "RepoImportUnavailable" (get (json/read-str (:body response)) "error")))
            (is (not (.contains ^String (:body response) "private disk path")))))
        (is (= before (imports/state)))
        (is (= 200 (:status (handler (req account bytes)))))
        (is (closed? @channels))))))

(deftest disk-read-failure-during-publication-rolls-back-every-table
  (let [{:keys [account did key handler]} (env) f (imports/repo-car did key {"com.example.record/a" {"value" "new"}})
        before (imports/state) verified? (atom false) reads (atom 0)
        stage staging/stage! verify repository/verify-blocks channels (atom []) original tempfile/open-channel!]
    (with-redefs [tempfile/open-channel! #(let [c (original)] (swap! channels conj c) c)
                  staging/stage! (fn [& args]
                                   (let [result (apply stage args) load-block (:load-block result)]
                                     (assoc result :load-block
                                       (fn [cid]
                                         (when (and @verified? (= 2 (swap! reads inc)))
                                           (throw (IOException. "private staged file failure")))
                                         (load-block cid)))))
                  repository/verify-blocks (fn [& args] (let [result (apply verify args)] (reset! verified? true) result))]
      (let [response (handler (req account (:car f)))]
        (is (= 503 (:status response)))
        (is (not (.contains ^String (:body response) "private staged file failure")))))
    (is (= 2 @reads) "Failure happens after the first reachable block was inserted")
    (is (= before (imports/state)))
    (is (closed? @channels))
    (is (= 200 (:status (handler (req account (:car f))))))))

(deftest upload-holds-no-connection-or-account-lock-and-rechecks-revocation
  (let [{:keys [settings account did key]} (env) f (imports/repo-car did key {"com.example.record/a" {}})
        database fixture/*database*
        changed? (atom false) before (imports/state)]
    (with-open [pool (db/open-pool! database {:maximum-size 1 :timeout-ms 500})]
      (let [handler (app/handler settings pool)
            input (tracked (:car f)
                    (fn [_]
                      (when (compare-and-set! changed? false true)
                        (db/transact! pool
                          (fn [conn]
                            (db/execute! conn "SET LOCAL lock_timeout = '1s'")
                            (db/query conn "SELECT did FROM accounts WHERE did = ? FOR UPDATE" did)
                            (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ?" did))))))]
        (is (= 401 (:status (handler (assoc (req account (:car f)) :body input)))))))
    (is @changed?)
    (is (= before (imports/state)))))

(deftest chunked-http-import-enforces-capacity-and-length-limit
  (let [{:keys [settings account did key]} (env) f (imports/repo-car did key {"com.example.record/a" {"text" "chunked"}})
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (doseq [[maximum expected] [[8 413] [importer/max-size 200]]]
          (with-redefs [importer/max-size maximum]
            (let [publisher (HttpRequest$BodyPublishers/ofInputStream
                              (reify Supplier (get [_] (ByteArrayInputStream. (:car f)))))
                  request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" (:port server) path)))
                              (.timeout (Duration/ofSeconds 10))
                              (.header "Authorization" (str "Bearer " (:accessJwt account)))
                              (.header "Content-Type" "application/vnd.ipld.car") (.POST publisher) .build)
                  response (.send client request (HttpResponse$BodyHandlers/ofString))]
              (is (= -1 (.contentLength publisher)))
              (is (= expected (.statusCode response)))))))
      (is (= ["a"] (mapv :rkey (:paths (repository/verify-car (imports/export did) did key)))))
      (finally ((:stop! server))))))

(deftest disconnected-http-import-cleans-up-its-private-file
  (let [{:keys [settings account did key]} (env) f (imports/repo-car did key {"com.example.record/a" {"data" (byte-array 100000)}})
        before (imports/state) opened (promise) original tempfile/open-channel!
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-redefs [tempfile/open-channel! #(let [channel (original)] (deliver opened channel) channel)]
        (with-open [socket (Socket. "127.0.0.1" (:port server))]
          (let [out (.getOutputStream socket)]
            (.write out (.getBytes (str "POST " path " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/vnd.ipld.car\r\nAuthorization: Bearer "
                                       (:accessJwt account) "\r\nContent-Length: " (alength ^bytes (:car f)) "\r\n\r\n") "US-ASCII"))
            (.write out ^bytes (:car f) 0 200) (.flush out))
          (is (instance? FileChannel (deref opened 3000 nil)))
          (.setSoLinger socket true 0))
        (let [channel (deref opened 3000 nil)]
          (when channel
            (loop [left 250]
              (when (and (pos? left) (.isOpen ^FileChannel channel)) (Thread/sleep 20) (recur (dec left))))
            (is (not (.isOpen ^FileChannel channel)))))
        (is (= before (imports/state))))
      (finally ((:stop! server))))))
