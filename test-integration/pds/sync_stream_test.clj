(ns pds.sync-stream-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.api.sync :as sync]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.http-test :as ht]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.mst :as mst]
            [pds.protocol.repository :as repository]
            [pds.repo :as repo]
            [pds.repo-export :as export]
            [pds.repo-import-test :as imports]
            [pds.response-body :as body]
            [pds.server-api-test :as api]
            [pds.tempfile :as tempfile])
  (:import [java.io InputStream]
           [java.net.http HttpClient]
           [java.nio.channels FileChannel]
           [java.util.concurrent Semaphore]))

(use-fixtures :each fixture/isolated-database)
(defn tx [f] (db/transact! fixture/*ds* f))
(defn seed! []
  (let [settings (api/settings) did (:did (imports/local! settings))
        values (mapv (fn [i] {"$type" "com.example.note" "n" i "text" (apply str (repeat 10000 "x"))}) (range 20))
        writes (tx #(repo/apply-writes! % settings did
                      (mapv (fn [i value] {:action :create :collection "com.example.note" :rkey (str i) :value value}) (range) values) nil))]
    {:settings settings :did did :writes writes :values values :handler (app/handler settings fixture/*ds*)}))
(defn request [did method query]
  {:request-method :get :uri (str "/xrpc/com.atproto.sync." method) :query-string (str "did=" did query)})
(defn consume [response]
  (with-open [b (:body response)] (.readAllBytes ^InputStream (:input b))))

(deftest streamed-partial-cars-are-complete-without-the-buffered-encoders
  (let [{:keys [settings did handler writes values]} (seed!)
        cid (get-in writes [:results 0 :cid]) key (imports/local-key settings did)
        original sync/read-block reads (atom [])]
    (with-redefs [car/encode (fn [& _] (throw (ex-info "Buffered CAR output forbidden" {})))
                  mst/proof (fn [& _] (throw (ex-info "Buffered proof traversal forbidden" {})))
                  sync/read-block (fn [conn did cid] (swap! reads conj cid) (original conn did cid))]
      (let [response (handler (request did "getBlocks" (apply str (repeat 100 (str "&cids=" cid)))))
            bytes (consume response) decoded (car/decode bytes)]
        (is (body/stream? (:body response)))
        (is (= 200 (:status response)))
        (is (= [cid] @reads) "Duplicate requests do not cause extra block loads")
        (is (= #{cid} (set (keys (:blocks decoded)))))
        (is (= [] (:roots decoded)))
        (is (= (str (alength bytes)) (get-in response [:headers "Content-Length"]))))
      (doseq [rkey ["0" "19" "absent"]]
        (let [response (handler (request did "getRecord" (str "&collection=com.example.note&rkey=" rkey)))
              verified (repository/verify-record (consume response) did key "com.example.note" rkey)]
          (is (= 200 (:status response)))
          (is (= (when-not (= "absent" rkey) (get values (Integer/parseInt rkey))) (:record verified)))
          (is (= (get-in writes [:commit :cid]) (:head verified))))))))

(deftest partial-limits-errors-and-shared-admission-clean-up-prepared-files
  (let [{:keys [settings did handler writes]} (seed!)
        cid (get-in writes [:results 0 :cid]) cid2 (get-in writes [:results 1 :cid])
        size (tx #(alength ^bytes (sync/read-block % did cid)))
        channels (atom []) original tempfile/open-channel!
        call #(handler (request did "getBlocks" %))]
    (with-redefs [tempfile/open-channel! #(let [c (original)] (swap! channels conj c) c)
                  sync/max-block-bytes size]
      (let [response (call (str "&cids=" cid "&cids=" cid))]
        (is (= 200 (:status response)))
        (consume response))
      (let [response (call (str "&cids=" cid "&cids=" cid2))]
        (is (= 413 (:status response)))
        (is (= "PayloadTooLarge" (get (json/read-str (:body response)) "error"))))
      (is (every? #(not (.isOpen ^FileChannel %)) @channels)))
    (with-redefs-fn {#'pds.repo-export/permits (Semaphore. 1)}
      (fn []
        (with-open [full (:body (export/response! fixture/*ds* settings did))]
          (is (= 503 (:status (call (str "&cids=" cid))))))
        (let [response (call (str "&cids=" cid))]
          (is (= 200 (:status response))) (consume response))))
    (let [foreign (tx #(repo/block! % (codec/encode {"$type" "com.example.private"})))]
      (is (= 400 (:status (call (str "&cids=" cid "&cids=" foreign))))))
    (tx #(db/execute! % "UPDATE repo_blocks SET content = ? WHERE cid = ?" (byte-array [1 2 3]) cid))
    (is (= 500 (:status (call (str "&cids=" cid)))))
    (is (= 500 (:status (handler (request did "getRecord" "&collection=com.example.note&rkey=0")))))))

(deftest head-closes-partial-cars-and-full-export-limit-does-not-limit-proofs
  (let [{:keys [settings did writes]} (seed!)
        channels (atom []) original tempfile/open-channel!
        server (http/start! settings (app/handler (assoc settings :repo-export-max-bytes 1) fixture/*ds*))]
    (try
      (with-redefs [tempfile/open-channel! #(let [c (original)] (swap! channels conj c) c)]
        (with-open [client (HttpClient/newHttpClient)]
          (doseq [path [(str "/xrpc/com.atproto.sync.getBlocks?did=" did "&cids=" (get-in writes [:results 0 :cid]))
                        (str "/xrpc/com.atproto.sync.getRecord?did=" did "&collection=com.example.note&rkey=0")]]
            (let [response (ht/request client (:port server) "HEAD" path)]
              (is (= 200 (.statusCode response)))
              (is (= "" (.body response)))
              (is (pos? (Long/parseLong (.orElse (.firstValue (.headers response) "Content-Length") "0")))))))
        (loop [remaining 100]
          (when (and (pos? remaining) (some #(.isOpen ^FileChannel %) @channels))
            (Thread/sleep 20) (recur (dec remaining))))
        (is (= 2 (count @channels)))
        (is (every? #(not (.isOpen ^FileChannel %)) @channels)))
      (finally ((:stop! server))))))
