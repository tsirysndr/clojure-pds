(ns pds.api.sync
  (:require [pds.accounts :as accounts]
            [pds.api.repo :as repo-api]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.mst :as mst]
            [pds.protocol.syntax :as syntax]
            [pds.repo :as repo]
            [pds.request :as request]))

(defn car-response [root blocks]
  (when (> (reduce + 0 (map #(alength ^bytes %) (vals blocks))) (* 63 1024 1024))
    (errors/raise! 413 "PayloadTooLarge" "Requested blocks exceed the response limit"))
  {:status 200 :headers {"Content-Type" "application/vnd.ipld.car"} :body (car/encode root blocks)})

(defn read-block [conn cid]
  (:content (first (db/query conn "SELECT content FROM repo_blocks WHERE cid = ?" cid))))

(defn active! [conn did]
  (when-not (syntax/did? did) (errors/invalid! "Invalid DID"))
  (accounts/resolve-account conn did))

(defn routes [ds _settings]
  {"/xrpc/com.atproto.sync.getBlocks"
   {:method :get
    :handler
    (fn [r]
      (let [params (request/query-params r #{"cids"}) cids (get params "cids")]
        (when-not (and (vector? cids) (<= 1 (count cids) 100)) (errors/invalid! "Expected 1 to 100 CIDs"))
        (doseq [cid cids] (repo-api/cid! cid))
        (db/transact! ds
          (fn [conn]
            (let [account (active! conn (get params "did"))
                  blocks (into {} (map (fn [cid]
                                        [cid (or (:content (first (db/query conn
                                          "SELECT b.content FROM repo_block_owners o JOIN repo_blocks b ON b.cid = o.cid WHERE o.did = ? AND o.cid = ?"
                                          (:did account) cid)))
                                                 (errors/raise! 400 "BlockNotFound" "Block was not found in this repository"))]) (distinct cids)))]
              (car-response nil blocks))))))}
   "/xrpc/com.atproto.sync.getRecord"
   {:method :get
    :handler
    (fn [r]
      (let [params (request/query-params r) collection (get params "collection") rkey (get params "rkey")]
        (repo/path! collection rkey)
        (db/transact! ds
          (fn [conn]
            (let [account (active! conn (get params "did"))
                  state (repo/state conn (:did account))
                  head (read-block conn (:head state))
                  root (:cid (get (codec/decode head) "data"))
                  proof (mst/proof root (str collection "/" rkey) #(read-block conn %))]
              (car-response (:head state) (assoc (:blocks proof) (:head state) head)))))))}})
