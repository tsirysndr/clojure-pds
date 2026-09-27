(ns pds.repo
  (:require [pds.crypto :as crypto]
            [pds.block-index :as block-index]
            [pds.blob-refs :as blob-refs]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.events :as events]
            [pds.lexicon :as lexicon]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.mst :as mst]
            [pds.protocol.syntax :as syntax]))

(def next-tid (syntax/tid-generator))
(defn block! [conn data]
  (let [id (codec/cid data)]
    (db/execute! conn "INSERT INTO repo_blocks(cid, content) VALUES (?, ?) ON CONFLICT (cid) DO NOTHING" id data)
    id))
(defn state [conn did]
  (or (first (db/query conn "SELECT * FROM repositories WHERE did = ? FOR UPDATE" did))
      (errors/raise! 400 "RepoNotFound" "Repository was not found")))
(defn tree [conn did]
  (let [records (into {} (map (fn [{:keys [collection rkey cid]}] [(str collection "/" rkey) cid]))
                      (db/query conn "SELECT collection, rkey, cid FROM records WHERE did = ? ORDER BY collection, rkey" did))]
    (assoc (mst/build records) :records records)))
(defn commit!
  ([conn settings repo] (commit! conn settings repo []))
  ([conn settings repo ops]
  (let [{:keys [root blocks] :as tree} (tree conn (:did repo))
        rev (next-tid (:rev repo))
        unsigned {"did" (:did repo) "version" 3 "rev" rev "prev" nil "data" (codec/link root)}
        private (crypto/unseal (:master-key settings) (:did repo) (:signing_key repo))
        signed (assoc unsigned "sig" (crypto/sign "ES256" private (codec/encode unsigned)))]
    (doseq [[_ data] blocks] (block! conn data))
    (let [head (block! conn (codec/encode signed))]
      (doseq [cid (cons head (keys blocks))] (block-index/associate! conn (:did repo) cid))
      (db/execute! conn "INSERT INTO repo_block_owners(did, cid) SELECT did, cid FROM records WHERE did = ? ON CONFLICT DO NOTHING" (:did repo))
      (db/execute! conn "UPDATE repositories SET head = ?, rev = ? WHERE did = ?" head rev (:did repo))
      (when-not (:suppress-events? repo)
       (let [proof (mst/covering-proof tree (map #(get % "path") ops))
            record-cids (keep #(some-> (get % "cid") :cid) ops)
            relevant (reduce (fn [all cid] (assoc all cid (:content (first (db/query conn "SELECT content FROM repo_blocks WHERE cid = ?" cid)))))
                             (assoc proof head (codec/encode signed)) record-cids)
            previous (when (:head repo) (codec/decode (:content (first (db/query conn "SELECT content FROM repo_blocks WHERE cid = ?" (:head repo))))))]
        (when-let [handle (:announce-handle repo)]
          (events/append! conn (:did repo) "identity" {"did" (:did repo) "handle" handle})
          (events/account! conn (:did repo) "active"))
        (events/commit! conn (:did repo) head rev (:rev repo) (get previous "data") ops relevant)))
      {:cid head :rev rev}))))
(defn initialize!
  ([conn settings did] (initialize! conn settings did nil))
  ([conn settings did handle]
  (let [{:keys [private public]} (crypto/keypair)]
    (db/execute! conn "INSERT INTO repositories(did, signing_key, public_key) VALUES (?, ?, ?)"
                 did (crypto/seal (:master-key settings) did private) public)
    (commit! conn settings (assoc (state conn did) :announce-handle handle)))))

(defn path! [collection rkey]
  (when-not (and (syntax/nsid? collection) (syntax/record-key? rkey))
    (errors/invalid! "Invalid collection or record key")))
(defn stamp-records! [conn did rev]
  ;; New/changed records have a null revision only inside the write transaction.
  (db/execute! conn "UPDATE records SET repo_rev = ? WHERE did = ? AND repo_rev IS NULL" rev did))
(defn- check-blobs! [conn did value]
  (when (map? value)
    (when (= "blob" (get value "$type"))
      (let [blob (first (db/query conn "SELECT mime_type, size FROM blobs WHERE did = ? AND cid = ?"
                                 did (:cid (get value "ref"))))]
        (when-not (and blob (= (:mime_type blob) (get value "mimeType")) (= (:size blob) (get value "size")))
          (errors/invalid! "Blob is missing or does not match its metadata")))))
  (doseq [v (cond (map? value) (vals value) (vector? value) value :else [])]
    (check-blobs! conn did v)))
(defn record-value! [conn did collection rkey value validate]
  (when-not (and (map? value) (= collection (get value "$type")))
    (errors/invalid! "Record $type must match its collection"))
  (let [native (try (codec/from-json value) (catch Exception _ (errors/invalid! "Invalid AT Protocol record")))
        data (codec/encode native)
        validation-status (lexicon/validate-record! collection rkey native validate)]
    (when (> (alength data) 1000000) (errors/raise! 413 "PayloadTooLarge" "Record exceeds 1,000,000 bytes"))
    (check-blobs! conn did native)
    {:cid (block! conn data) :validation-status validation-status :record native}))
(defn apply-writes!
  "Caller owns transaction. Repository lock protects all swap checks and writes."
  ([conn settings did writes swap-commit]
   (apply-writes! conn settings did writes swap-commit (fn [_ _] nil)))
  ([conn settings did writes swap-commit authorize-write!]
  (let [repo (state conn did)]
    (when (and swap-commit (not= swap-commit (:head repo)))
      (errors/raise! 400 "InvalidSwap" "Repository commit has changed"))
    (when-not (and (vector? writes) (<= 1 (count writes) 200)) (errors/invalid! "Expected 1 to 200 writes"))
    (let [seen (atom #{}) ops (atom []) old-blobs (atom #{})
          results
          (mapv
           (fn [{:keys [action collection rkey value validate swap-record swap-record?]}]
             (let [rkey (or rkey (when (= action :create) (next-tid)))
                   _ (path! collection rkey)
                   path [collection rkey]
                   old (first (db/query conn "SELECT cid FROM records WHERE did = ? AND collection = ? AND rkey = ?" did collection rkey))]
               (authorize-write! collection (if (= :put action) (if old :update :create) action))
               (when (@seen path) (errors/invalid! "Duplicate record path in batch"))
               (swap! seen conj path)
               (swap! old-blobs into (map :cid (db/query conn "SELECT cid FROM record_blob_refs WHERE did = ? AND collection = ? AND rkey = ?" did collection rkey)))
               (when (and swap-record? (not= swap-record (:cid old)))
                 (errors/raise! 400 "InvalidSwap" "Record has changed"))
               (when (and (= action :create) old) (errors/raise! 400 "RecordAlreadyExists" "Record already exists"))
               (case action
                 :delete (do (when old (swap! ops conj {"action" "delete" "path" (str collection "/" rkey) "cid" nil "prev" (codec/link (:cid old))}))
                             (db/execute! conn "DELETE FROM records WHERE did = ? AND collection = ? AND rkey = ?" did collection rkey)
                             {:$type "com.atproto.repo.applyWrites#deleteResult"})
                 (:create :update :put)
                 (do
                   (when (and (= action :update) (nil? old)) (errors/raise! 400 "RecordNotFound" "Record does not exist"))
                   (let [{id :cid validation-status :validation-status record :record} (record-value! conn did collection rkey value validate)]
                     (when (not= id (:cid old))
                       (swap! ops conj (cond-> {"action" (if old "update" "create") "path" (str collection "/" rkey) "cid" (codec/link id)}
                                         old (assoc "prev" (codec/link (:cid old))))))
                     (db/execute! conn "INSERT INTO records(did, collection, rkey, cid) VALUES (?, ?, ?, ?)
                                         ON CONFLICT (did, collection, rkey) DO UPDATE
                                         SET cid = excluded.cid, repo_rev = CASE WHEN records.cid = excluded.cid THEN records.repo_rev ELSE NULL END"
                                  did collection rkey id)
                     (blob-refs/replace! conn did collection rkey record)
                     (cond-> {:$type (str "com.atproto.repo.applyWrites#" (if (= action :create) "create" "update") "Result")
                              :uri (str "at://" did "/" collection "/" rkey) :cid id}
                       validation-status (assoc :validationStatus validation-status))))
                 (errors/invalid! "Unknown write operation")))) writes)
          commit (commit! conn settings repo @ops)]
      (stamp-records! conn did (:rev commit))
      (blobs/remove-unreferenced! conn did @old-blobs)
      {:commit commit :results results}))))

(defn record [conn did collection rkey]
  (path! collection rkey)
  (when-let [row (first (db/query conn "SELECT r.cid, b.content FROM records r JOIN repo_blocks b ON b.cid = r.cid
                                      WHERE r.did = ? AND r.collection = ? AND r.rkey = ?" did collection rkey))]
    {:uri (str "at://" did "/" collection "/" rkey) :cid (:cid row)
     :value (codec/to-json (codec/decode (:content row)))}))
(defn export-car [conn did]
  (let [repo (state conn did)
        tree (tree conn did)
        records (db/query conn "SELECT r.cid, b.content FROM records r JOIN repo_blocks b ON b.cid = r.cid WHERE r.did = ?" did)
        head (first (db/query conn "SELECT content FROM repo_blocks WHERE cid = ?" (:head repo)))]
    (car/encode (:head repo) (into (assoc (:blocks tree) (:head repo) (:content head))
                                  (map (juxt :cid :content)) records))))
