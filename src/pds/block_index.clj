(ns pds.block-index
  (:require [pds.db :as db]
            [pds.protocol.codec :as codec]))

(defn associate! [conn did cid]
  (db/execute! conn "INSERT INTO repo_block_owners(did, cid) VALUES (?, ?) ON CONFLICT DO NOTHING" did cid))

(defn backfill!
  "Index the retained commit/MST history without following user-controlled links
  inside records (which may refer to another repository or external blob)."
  [conn]
  (doseq [{:keys [did head]} (db/query conn "SELECT did, head FROM repositories")]
    (let [heads (cons head (map :commit_cid (db/query conn "SELECT commit_cid FROM repo_events WHERE did = ? AND event_type = 'commit'" did)))]
      (loop [pending (mapv #(vector % :commit) (distinct heads)) seen #{}]
        (when-let [[cid kind] (peek pending)]
          (if (contains? seen cid)
            (recur (pop pending) seen)
            (let [data (:content (first (db/query conn "SELECT content FROM repo_blocks WHERE cid = ?" cid)))
                  _ (when-not (and data (= cid (codec/cid data)))
                      (throw (ex-info "Missing or corrupt repository history during block indexing" {:did did :cid cid})))
                  node (when (not= kind :record) (codec/decode data))
                  children (case kind
                             :commit [[(:cid (get node "data")) :mst]]
                             :mst (concat (when-let [left (get node "l")] [[(:cid left) :mst]])
                                          (mapcat (fn [entry]
                                                    (cond-> [[(:cid (get entry "v")) :record]]
                                                      (get entry "t") (conj [(:cid (get entry "t")) :mst]))) (get node "e")))
                             :record [])]
              (associate! conn did cid)
              (recur (into (pop pending) children) (conj seen cid)))))))))
