(ns pds.repo-export-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.repository :as repository]
            [pds.repo :as repo]
            [pds.repo-export :as export]
            [pds.repo-import-test :as imports]
            [pds.response-body :as body]
            [pds.server-api-test :as api]
            [pds.tempfile :as tempfile])
  (:import [java.io InputStream]
           [java.nio.channels FileChannel]
           [java.util Arrays]
           [java.util.concurrent Semaphore]))

(use-fixtures :each fixture/isolated-database)
(defn tx [f] (db/transact! fixture/*ds* f))
(defn bytes! [response]
  (with-open [b (:body response)] (.readAllBytes ^InputStream (:input b))))
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))
(defn write! [settings did key value]
  (tx #(repo/apply-writes! % settings did [{:action :put :collection "com.example.note" :rkey key :value value}] nil)))

(deftest committed-graph-export-excludes-history-and-arbitrary-record-links
  (let [settings (api/settings) did (:did (imports/local! settings))
        foreign (tx #(repo/block! % (codec/encode {"$type" "com.example.other" "private" true})))
        value {"$type" "com.example.note" "foreign" {"$link" foreign} "text" (apply str (repeat 20000 "x"))}
        old (write! settings did "old" {"$type" "com.example.note" "text" "old"})
        old-cid (get-in old [:results 0 :cid])]
    (tx #(repo/apply-writes! % settings did [{:action :delete :collection "com.example.note" :rkey "old"}] nil))
    (tx #(repo/apply-writes! % settings did
            (mapv (fn [i] {:action :create :collection "com.example.note" :rkey (str i) :value (assoc value "n" (quot i 2))}) (range 50)) nil))
    (tx #(db/execute! % "UPDATE records SET takedown_ref = 'test' WHERE did = ? AND rkey = '0'" did))
    (let [expected (car/decode (tx #(repo/export-car % did))) key (imports/local-key settings did)
          result (with-redefs [repo/tree (fn [& _] (throw (ex-info "Export must not rebuild the MST" {})))
                               repo/export-car (fn [& _] (throw (ex-info "Buffered export is forbidden" {})))
                               car/encode (fn [& _] (throw (ex-info "Whole-CAR encoding is forbidden" {})))]
                   (export/response! fixture/*ds* settings did))
          bytes (bytes! result) actual (car/decode bytes)]
      (is (body/stream? (:body result)))
      (is (= [(:status result) (get-in result [:headers "Content-Type"])] [200 "application/vnd.ipld.car"]))
      (is (= (alength bytes) (:length (:body result))))
      (is (= (:roots expected) (:roots actual)))
      (is (= (set (keys (:blocks expected))) (set (keys (:blocks actual)))))
      (is (every? (fn [[cid data]] (Arrays/equals ^bytes data ^bytes (get-in actual [:blocks cid]))) (:blocks expected)))
      (is (not (contains? (:blocks actual) foreign)))
      (is (not (contains? (:blocks actual) old-cid)))
      (is (= 50 (count (:paths (repository/verify-car bytes did key)))))
      ;; Canonical encoding of the same block map has the same size; duplicated
      ;; record values must not be emitted twice even when referenced twice.
      (is (= (alength bytes) (alength (car/encode (first (:roots actual)) (:blocks actual))))))))

(deftest prepared-export-remains-a-consistent-snapshot-after-new-writes
  (let [settings (api/settings) did (:did (imports/local! settings))
        initial (write! settings did "one" {"$type" "com.example.note" "text" "before"})
        prepared (export/response! fixture/*ds* settings did)]
    (try
      ;; A prepared body owns only its file: new writes can complete while it
      ;; waits for a slow client, and cannot alter any bytes of the old export.
      (write! settings did "one" {"$type" "com.example.note" "text" "after"})
      (let [verified (repository/verify-car (bytes! prepared) did (imports/local-key settings did))]
        (is (= (get-in initial [:commit :cid]) (:head verified)))
        (is (= "before" (get-in verified [:records (get-in initial [:results 0 :cid]) "text"]))))
      (finally (.close ^java.io.Closeable (:body prepared))))))

(deftest export-errors-and-capacity-close-files-and-remove-transaction-local-state
  (let [settings (api/settings) did (:did (imports/local! settings))
        channels (atom []) original tempfile/open-channel!]
    (with-redefs-fn {#'pds.repo-export/permits (Semaphore. 1)
                    #'tempfile/open-channel! #(let [channel (original)] (swap! channels conj channel) channel)}
      (fn []
        (is (= 413 (:status (error #(export/response! fixture/*ds* (assoc settings :repo-export-max-bytes 100) did)))))
        (is (every? #(not (.isOpen ^FileChannel %)) @channels))
        (with-open [b (:body (export/response! fixture/*ds* settings did))]
          (is (= "RepoExportBusy" (:error (error #(export/response! fixture/*ds* settings did))))))
        (let [cid (get-in (write! settings did "one" {"$type" "com.example.note" "text" "valid"}) [:results 0 :cid])]
          (tx #(db/execute! % "UPDATE repo_blocks SET content = ? WHERE cid = ?" (byte-array [1 2 3]) cid))
          (is (= :invalid-data (:type (error #(export/response! fixture/*ds* settings did))))))
        (is (every? #(not (.isOpen ^FileChannel %)) @channels))
        (with-open [conn (db/connection fixture/*ds*)]
          (is (empty? (db/query conn "SELECT 1 FROM pg_tables WHERE tablename = 'pds_car_export_seen'"))))))))

(deftest concurrent-writes-wait-for-snapshot-preparation-but-not-http-consumption
  (let [settings (api/settings) did (:did (imports/local! settings))
        initial (write! settings did "one" {"$type" "com.example.note" "text" "before"})
        entered (promise) release (promise) writing (promise) original car/write-block!
        export-task (atom nil) write-task (atom nil) result (atom nil)]
    (try
      (with-redefs [car/write-block! (fn [out cid data]
                                      (when (= cid (get-in initial [:commit :cid]))
                                        (deliver entered true)
                                        (when-not (= true (deref release 5000 false))
                                          (throw (ex-info "Export test barrier timed out" {}))))
                                      (original out cid data))]
        (reset! export-task (future (export/response! fixture/*ds* settings did)))
        (is (= true (deref entered 3000 :timeout)))
        (reset! write-task (future (deliver writing true)
                                  (write! settings did "one" {"$type" "com.example.note" "text" "after"})))
        (is (= true (deref writing 3000 :timeout)))
        (is (= :waiting (deref @write-task 100 :waiting)))
        (deliver release true)
        (reset! result (deref @export-task 5000 nil))
        (is (some? @result))
        (is (map? (deref @write-task 5000 nil)))
        (when @result
          (is (= (get-in initial [:commit :cid])
                 (:head (repository/verify-car (bytes! @result) did (imports/local-key settings did)))))))
      (finally
        (deliver release true)
        (when @export-task
          (let [response (deref @export-task 5000 nil)]
            (when response (.close ^java.io.Closeable (:body response)))))
        (when @write-task (deref @write-task 5000 nil))))))

(deftest pooled-connections-drop-temporary-state-before-delivery-and-can-be-reused
  (let [settings (api/settings) did (:did (imports/local! settings))
        database {:url (.getURL fixture/*ds*) :user (.getUser fixture/*ds*) :password (.getPassword fixture/*ds*)}]
    (with-open [pool (db/open-pool! database {:maximum-size 1 :timeout-ms 500})]
      (is (= 413 (:status (error #(export/response! pool (assoc settings :repo-export-max-bytes 100) did)))))
      (let [pids (atom #{})]
        ;; Cross pgjdbc's prepared-statement threshold on a single physical
        ;; connection, including after a failed export transaction.
        (dotimes [_ 8]
          (with-open [b (:body (export/response! pool settings did))]
            (with-open [conn (db/connection pool)]
              (let [row (first (db/query conn "SELECT pg_backend_pid() AS pid, to_regclass('pg_temp.pds_car_export_seen') IS NULL AS absent"))]
                (swap! pids conj (:pid row))
                (is (:absent row))))
            (is (seq (.readAllBytes ^InputStream (:input b))))))
        (is (= 1 (count @pids)))))))
