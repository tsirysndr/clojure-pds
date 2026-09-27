(ns pds.repo-import
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.auth :as auth]
            [pds.block-index :as block-index]
            [pds.blob-refs :as blob-refs]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.events :as events]
            [pds.identity :as identity]
            [pds.protocol.repository :as repository]
            [pds.repo :as repo]
            [pds.request :as request])
  (:import [java.util.concurrent Semaphore]))

(def max-size (* 64 1024 1024))
;; Bound simultaneous buffered imports across all HTTP handlers in this process.
(defonce ^:private permits (Semaphore. 2))

(defn- snapshot! [conn settings request]
  (let [account (auth/authenticate! conn settings request {:allow-deactivated? true})]
    (auth/require-management! account :account "repo")
    {:account account :repo (repo/state conn (:did account))
     :source-document (:source_document (first (db/query conn "SELECT source_document::text FROM account_imports WHERE did = ?" (:did account))))}))

(defn- version [snapshot]
  [(:did (:account snapshot)) (:status (:account snapshot))
   (:head (:repo snapshot)) (vec (:public_key (:repo snapshot))) (:source-document snapshot)])

(defn import! [ds settings request]
  (let [snapshot (db/transact! ds #(snapshot! % settings request))]
    (when-not (= "application/vnd.ipld.car"
                 (some-> (get-in request [:headers "content-type"]) (str/split #";") first str/trim str/lower-case))
      (errors/raise! 415 "InvalidRequest" "Expected application/vnd.ipld.car"))
    (when-not (.tryAcquire permits)
      (errors/raise! 503 "RepoImportBusy" "Repository import capacity is busy; retry later"))
    (try
      ;; Reading and verifying a potentially large upload holds no database locks.
      (let [bytes (request/body-bytes request max-size)
            key (if-let [document (:source-document snapshot)]
                  (identity/signing-key (json/read-str document))
                  {:algorithm "ES256" :public (:public_key (:repo snapshot))})
            verified (try (repository/verify-car bytes (:did (:account snapshot)) key)
                          (catch Exception _ (errors/invalid! "Invalid repository CAR, identity or signature")))]
        (db/transact! ds
          (fn [conn]
            (let [current (snapshot! conn settings request)
                  account (:account current) did (:did account)
                  old-blobs (mapv :cid (db/query conn "SELECT DISTINCT cid FROM record_blob_refs WHERE did = ?" did))]
              (when-not (= (version snapshot) (version current))
                (errors/raise! 409 "InvalidSwap" "Account or repository changed during import; retry with its current state"))
              ;; Only verified reachable blocks gain ownership. Unrelated CAR
              ;; data and record links cannot grant access to another account.
              (doseq [[cid content] (:blocks verified)]
                (repo/block! conn content)
                (block-index/associate! conn did cid))
              (db/execute! conn "DELETE FROM records WHERE did = ?" did)
              (doseq [{:keys [collection rkey cid]} (:paths verified)]
                (db/execute! conn "INSERT INTO records(did, collection, rkey, cid) VALUES (?, ?, ?, ?)" did collection rkey cid)
                (blob-refs/replace! conn did collection rkey (get (:records verified) cid)))
              ;; Re-sign with the destination's key and a revision newer than
              ;; both heads. Large replacements use a sync checkpoint, not an
              ;; incomplete or oversized inductive commit proof.
              (let [commit (repo/commit! conn settings (assoc (:repo current) :suppress-events? true
                                                            :rev (last (sort [(:rev (:repo current)) (:rev verified)]))))]
                (repo/stamp-records! conn did (:rev commit)))
              (db/execute! conn "UPDATE account_imports SET repository_imported = true WHERE did = ?" did)
              (blobs/remove-unreferenced! conn did old-blobs)
              (when (= "active" (:status account)) (events/sync! conn did))
              nil))))
      (finally (.release permits)))))
