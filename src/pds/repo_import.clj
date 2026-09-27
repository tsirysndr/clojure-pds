(ns pds.repo-import
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.auth :as auth]
            [pds.block-index :as block-index]
            [pds.blob-refs :as blob-refs]
            [pds.blobs :as blobs]
            [pds.car-staging :as staging]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.events :as events]
            [pds.identity :as identity]
            [pds.protocol.repository :as repository]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.tempfile :as tempfile])
  (:import [java.io ByteArrayInputStream InputStream IOException]
           [java.util.concurrent Semaphore]))

(def max-size (* 64 1024 1024))
;; Bound simultaneous staged imports across all HTTP handlers in this process.
(defonce ^:private permits (Semaphore. 2))

(defn- snapshot! [conn settings request]
  (let [account (auth/authenticate! conn settings request {:allow-deactivated? true})]
    (auth/require-management! account :account "repo")
    {:account account :repo (repo/state conn (:did account))
     :source-document (:source_document (first (db/query conn "SELECT source_document::text FROM account_imports WHERE did = ?" (:did account))))}))

(defn- version [snapshot]
  [(:did (:account snapshot)) (:status (:account snapshot))
   (:head (:repo snapshot)) (vec (:public_key (:repo snapshot))) (:source-document snapshot)])

(defn- input! [request]
  (let [declared (when-let [value (get-in request [:headers "content-length"])]
                   (when-not (re-matches #"[0-9]{1,18}" value) (errors/invalid! "Invalid Content-Length"))
                   (Long/parseLong value))
        _ (when (and declared (> declared max-size))
            (errors/raise! 413 "PayloadTooLarge" "Repository import exceeds the limit"))
        ^InputStream source (or (:body request) (ByteArrayInputStream. (byte-array 0)))
        count (volatile! 0) deadline (+ (System/nanoTime) 30000000000)
        read! (fn [f]
                (when (> (System/nanoTime) deadline) (errors/raise! 408 "RequestTimeout" "Repository upload timed out"))
                (let [n (try (f) (catch IOException _ (errors/invalid! "Incomplete repository upload")))]
                  (if (= n -1)
                    (when (and declared (not= declared @count)) (errors/invalid! "Incomplete repository upload"))
                    (do (vswap! count + n)
                        (when (and declared (> @count declared)) (errors/invalid! "Invalid repository upload length"))))
                  n))]
    (proxy [InputStream] []
      (read
        ([] (let [value (volatile! -1)]
              (read! #(let [b (.read source)] (vreset! value b) (if (= b -1) -1 1)))
              @value))
        ([buffer offset length] (read! #(.read source buffer offset length)))))))

(defn import! [ds settings request]
  (let [snapshot (db/transact! ds #(snapshot! % settings request))]
    (when-not (= "application/vnd.ipld.car"
                 (some-> (get-in request [:headers "content-type"]) (str/split #";") first str/trim str/lower-case))
      (errors/raise! 415 "InvalidRequest" "Expected application/vnd.ipld.car"))
    (when-not (.tryAcquire permits)
      (errors/raise! 503 "RepoImportBusy" "Repository import capacity is busy; retry later"))
    (try
      ;; Reading and verifying the upload holds no database connection or lock.
      (let [input (input! request)]
        (with-open [channel (tempfile/open-channel!)]
          (let [{:keys [roots load-block]} (staging/stage! channel input max-size)
                key (if-let [document (:source-document snapshot)]
                      (identity/signing-key (json/read-str document))
                      {:algorithm "ES256" :public (:public_key (:repo snapshot))})
                verified (repository/verify-blocks roots load-block (:did (:account snapshot)) key)]
            (db/transact! ds
              (fn [conn]
                (let [current (snapshot! conn settings request)
                      account (:account current) did (:did account)
                      old-blobs (mapv :cid (db/query conn "SELECT DISTINCT cid FROM record_blob_refs WHERE did = ?" did))
                      takedowns (into {} (map (fn [row] [[(:collection row) (:rkey row)] (:takedown_ref row)]))
                                     (db/query conn "SELECT collection, rkey, takedown_ref FROM records WHERE did = ? AND takedown_ref IS NOT NULL" did))]
                  (when-not (= (version snapshot) (version current))
                    (errors/raise! 409 "InvalidSwap" "Account or repository changed during import; retry with its current state"))
                  ;; Only verified reachable blocks gain ownership; payloads are
                  ;; read from disk individually, never accumulated into a map.
                  (doseq [cid (sort (:block-cids verified))]
                    (repo/block! conn (load-block cid))
                    (block-index/associate! conn did cid))
                  (db/execute! conn "DELETE FROM records WHERE did = ?" did)
                  (doseq [{:keys [collection rkey cid]} (:paths verified)]
                    (db/execute! conn "INSERT INTO records(did, collection, rkey, cid, takedown_ref) VALUES (?, ?, ?, ?, ?)"
                                 did collection rkey cid (get takedowns [collection rkey]))
                    (blob-refs/replace! conn did collection rkey (codec/decode (load-block cid) 1000000)))
                  ;; The validated canonical root is unchanged. Re-sign it with
                  ;; the destination key and a revision newer than both heads.
                  (let [commit (repo/commit-import! conn settings (:repo current) (:root verified) (:rev verified))]
                    (repo/stamp-records! conn did (:rev commit)))
                  (db/execute! conn "UPDATE account_imports SET repository_imported = true WHERE did = ?" did)
                  (blobs/remove-unreferenced! conn did old-blobs)
                  (when (= "active" (:status account)) (events/sync! conn did))
                  nil))))))
      (catch IOException _ (errors/raise! 503 "RepoImportUnavailable" "Repository import storage is unavailable"))
      (catch clojure.lang.ExceptionInfo error
        (cond (:car-too-large (ex-data error))
              (errors/raise! 413 "PayloadTooLarge" "Repository import exceeds the limit")
              (= :invalid-data (:type (ex-data error)))
              (errors/invalid! "Invalid repository CAR, identity or signature")
              :else (throw error)))
      (finally (.release permits)))))
