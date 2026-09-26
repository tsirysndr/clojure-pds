(ns pds.blob-refs
  (:require [pds.db :as db]
            [pds.protocol.codec :as codec]))

(defn- raw-cid? [value]
  (try (= 85 (aget (codec/cid-bytes value) 1)) (catch Exception _ false)))

(defn references
  "Find valid modern and legacy-shaped blob references in native record data.
  Ignore malformed historical objects and ordinary CID links/strings. Record
  write validation remains separate; indexing never rewrites imported data."
  [value]
  (letfn [(walk [value found]
            (cond
              (instance? pds.protocol.codec.Link value) found
              (map? value)
              (let [mime (get value "mimeType")
                    cid (cond
                          (and (= "blob" (get value "$type"))
                               (instance? pds.protocol.codec.Link (get value "ref"))
                               (integer? (get value "size")) (<= 0 (get value "size")))
                          (:cid (get value "ref"))
                          (and (not (contains? value "$type")) (string? (get value "cid"))) (get value "cid"))
                    found (if (and (string? mime) (seq mime) (raw-cid? cid)) (conj found cid) found)]
                (reduce #(walk %2 %1) found (vals value)))
              (vector? value) (reduce #(walk %2 %1) found value)
              :else found))]
    (walk value #{})))

(defn replace! [conn did collection rkey value]
  (db/execute! conn "DELETE FROM record_blob_refs WHERE did = ? AND collection = ? AND rkey = ?" did collection rkey)
  (doseq [cid (references value)]
    (db/execute! conn "INSERT INTO record_blob_refs(did, collection, rkey, cid) VALUES (?, ?, ?, ?)" did collection rkey cid)))

(defn missing [conn did cursor limit]
  (db/query conn "SELECT r.cid, min((r.collection || '/' || r.rkey) COLLATE \"C\") AS path
                 FROM record_blob_refs r LEFT JOIN blobs b ON b.did = r.did AND b.cid = r.cid
                 WHERE r.did = ? AND b.cid IS NULL AND (?::text IS NULL OR r.cid COLLATE \"C\" > ? COLLATE \"C\")
                 GROUP BY r.cid ORDER BY r.cid COLLATE \"C\" LIMIT ?" did cursor cursor limit))

(defn backfill! [conn]
  (doseq [{:keys [did collection rkey content]}
          (db/query conn "SELECT r.did, r.collection, r.rkey, b.content FROM records r JOIN repo_blocks b ON b.cid = r.cid")]
    (replace! conn did collection rkey (codec/decode content))))
