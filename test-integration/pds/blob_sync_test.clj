(ns pds.blob-sync-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.blob-refs :as refs]
            [pds.blob-refs-test :refer [blob]]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.plc-provision-test :refer [rows]]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.repo-import :as repo-import]
            [pds.repo-import-test :as imports]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))
(use-fixtures :each fixture/isolated-database)

(defn listing [handler did query]
  (let [response (handler {:request-method :get :uri "/xrpc/com.atproto.sync.listBlobs"
                           :query-string (str "did=" did query)})]
    (assoc response :body (json/read-str (:body response)))))

(deftest blob-listing-follows-current-record-revisions-over-http
  (let [settings (api/settings) account (imports/local! settings) did (:did account)
        data (mapv #(byte-array [%]) [1 2 3]) native (mapv blob data)
        cids (mapv #(:cid (get % "ref")) native)
        write (fn [rkey index text] {:action :put :collection "com.example.file" :rkey rkey
                                     :value {"$type" "com.example.file" "file" (codec/to-json (nth native index)) "text" text}})
        apply! #(db/transact! fixture/*ds* (fn [conn] (repo/apply-writes! conn settings did % nil)))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (db/transact! fixture/*ds* (fn [conn] (doseq [bytes data] (blobs/store! conn settings did bytes "image/png"))))
      (with-open [client (HttpClient/newHttpClient)]
        (let [list! #(api/xrpc client (:port server) "GET" (str "com.atproto.sync.listBlobs?did=" did %) nil nil)
              ids #(get-in (list! %) [:body "cids"])]
          (is (= [] (ids "")) "Unreferenced uploads are not synchronization data")
          (let [first-rev (get-in (apply! [(write "a" 0 "one") (write "b" 1 "two") (write "c" 0 "shared")]) [:commit :rev])
                sorted (vec (sort (take 2 cids))) page (:body (list! "&limit=1"))]
            (is (= sorted (ids "")))
            (is (= [(first sorted)] (get page "cids")))
            (is (= [(second sorted)] (ids (str "&limit=1&cursor=" (get page "cursor")))))
            (is (= [] (ids (str "&since=" first-rev))))
            (let [second-rev (get-in (apply! [(write "a" 0 "changed")]) [:commit :rev])]
              (is (= [(first cids)] (ids (str "&since=" first-rev))))
              (is (= [] (ids (str "&since=" second-rev))))
              (apply! [(write "a" 0 "changed")])
              (is (= [] (ids (str "&since=" second-rev))) "No-op puts do not change record revisions")
              (let [before (rows "SELECT rkey, cid, repo_rev FROM records WHERE did = ? ORDER BY rkey" did)]
                (is (thrown? Exception
                      (db/transact! fixture/*ds* (fn [conn]
                                                  (repo/apply-writes! conn settings did [(write "a" 2 "rollback")] nil)
                                                  (throw (ex-info "Rollback" {}))))))
                (is (= before (rows "SELECT rkey, cid, repo_rev FROM records WHERE did = ? ORDER BY rkey" did))))
              (apply! [{:action :delete :collection "com.example.file" :rkey "a"}])
              (is (= [] (ids (str "&since=" first-rev))))
              (is (= sorted (ids "")) "An older surviving record still references the shared blob")
              (apply! [{:action :delete :collection "com.example.file" :rkey "c"}])
              (is (= [(second cids)] (ids "")))))
          (doseq [query ["&since=" "&since=bad" "&limit=0" "&cursor=invalid"]]
            (is (= 400 (:status (list! query)))))))
      (finally ((:stop! server))))))

(deftest import-stamps-destination-revisions-and-lists-missing-referenced-content
  (let [settings (api/settings) account (imports/local! settings) did (:did account)
        key (imports/local-key settings did) before (:rev (first (rows "SELECT rev FROM repositories WHERE did = ?" did)))
        native (blob (byte-array [9])) cid (:cid (get native "ref"))
        imported (imports/repo-car did key {"com.example.file/a" {"file" native}})
        handler (app/handler settings fixture/*ds*)]
    (repo-import/import! fixture/*ds* settings (imports/import-request (:accessJwt account) (:car imported)))
    (let [rev (:rev (first (rows "SELECT rev FROM repositories WHERE did = ?" did)))]
      (is (= [{:repo_rev rev}] (rows "SELECT repo_rev FROM records WHERE did = ?" did)))
      (is (= [cid] (get-in (listing handler did (str "&since=" before)) [:body "cids"])))
      (is (= [] (get-in (listing handler did (str "&since=" rev)) [:body "cids"])))
      (is (= [] (rows "SELECT cid FROM blobs WHERE did = ?" did)))
      (repo-import/import! fixture/*ds* settings (imports/import-request (:accessJwt account) (:car (imports/repo-car did key {}))))
      (is (= [] (get-in (listing handler did "") [:body "cids"]))))))

(deftest revision-upgrade-uses-a-conservative-baseline-without-changing-the-head
  ;; Replays a prefix of the PostgreSQL migration chain; the SQLite backend
  ;; ships one consolidated baseline with no partial history.
  (when (fixture/postgres?)
  (let [all db/migrations]
    (with-redefs [db/migrations (vec (take 20 all))]
      (fixture/isolated-database
        (fn []
          (let [settings (api/settings) account (imports/local! settings) did (:did account)
                native (blob (byte-array [8])) value {"file" native} data (codec/encode value)]
            (db/transact! fixture/*ds*
              (fn [conn]
                (let [cid (repo/block! conn data)]
                  (db/execute! conn "INSERT INTO records(did, collection, rkey, cid) VALUES (?, 'com.example.file', 'one', ?)" did cid))
                (refs/replace! conn did "com.example.file" "one" value)
                (repo/commit! conn settings (assoc (repo/state conn did) :suppress-events? true))))
            (let [before (rows "SELECT head, rev FROM repositories") rev (:rev (first before))]
              (with-redefs [db/migrations all] (is (db/migrate! fixture/*ds*)))
              (is (= before (rows "SELECT head, rev FROM repositories")))
              (is (= [{:repo_rev rev}] (rows "SELECT repo_rev FROM records")))
              (let [handler (app/handler settings fixture/*ds*)]
                (is (= [(:cid (get native "ref"))] (get-in (listing handler did "&since=2222222222222") [:body "cids"])))
                (is (= [] (get-in (listing handler did (str "&since=" rev)) [:body "cids"]))))))))))))
