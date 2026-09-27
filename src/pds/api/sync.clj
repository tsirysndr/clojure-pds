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
            [pds.repo-export :as export]
            [pds.request :as request]
            [pds.signing-state :as signing-state]))

(def max-block-bytes (* 63 1024 1024))
(def max-car-bytes (* 64 1024 1024))

(defn- emitter [out]
  (let [seen (atom #{}) total (atom 0)]
    (fn [cid data]
      (when-not (contains? @seen cid)
        (when (> (+ @total (alength ^bytes data)) max-block-bytes)
          (errors/raise! 413 "PayloadTooLarge" "Requested blocks exceed the response limit"))
        (car/write-block! out cid data)
        (swap! total + (alength ^bytes data))
        (swap! seen conj cid)))))

(defn read-block [conn did cid]
  (:content (first (db/query conn "SELECT b.content FROM repo_block_owners o JOIN repo_blocks b ON b.cid = o.cid
                                  WHERE o.did = ? AND o.cid = ? AND octet_length(b.content) <= 1048576" did cid))))

(defn active! [conn did]
  (when-not (syntax/did? did) (errors/invalid! "Invalid DID"))
  (signing-state/ready! conn did)
  (accounts/resolve-account conn did))

(defn routes [ds _settings]
  {"/xrpc/com.atproto.sync.getBlocks"
   {:method :get
    :handler
    (fn [r]
      (let [params (request/query-params r #{"cids"}) cids (get params "cids")]
        (when-not (and (vector? cids) (<= 1 (count cids) 100)) (errors/invalid! "Expected 1 to 100 CIDs"))
        (doseq [cid cids] (repo-api/cid! cid))
        (export/staged-response! max-car-bytes
          (fn [out]
            (db/transact! ds
              (fn [conn]
                (let [account (active! conn (get params "did")) emit! (emitter out)]
                  (car/write-header! out nil)
                  (doseq [cid (sort (distinct cids))]
                    (emit! cid (or (read-block conn (:did account) cid)
                                   (errors/raise! 400 "BlockNotFound" "Block was not found in this repository")))))))))))}
   "/xrpc/com.atproto.sync.getRecord"
   {:method :get
    :handler
    (fn [r]
      (let [params (request/query-params r) collection (get params "collection") rkey (get params "rkey")]
        (repo/path! collection rkey)
        (export/staged-response! max-car-bytes
          (fn [out]
            (db/transact! ds
              (fn [conn]
                (let [account (active! conn (get params "did"))
                      state (repo/state conn (:did account))
                      head (read-block conn (:did account) (:head state))
                      root (:cid (get (codec/decode head) "data"))
                      emit! (emitter out)]
                  (car/write-header! out (:head state))
                  (emit! (:head state) head)
                  (mst/visit-proof! root (str collection "/" rkey)
                                    #(read-block conn (:did account) %) emit! true))))))))}})
