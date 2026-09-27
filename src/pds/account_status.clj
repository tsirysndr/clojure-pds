(ns pds.account-status
  (:require [clojure.string :as str]
            [pds.auth :as auth]
            [pds.db :as db]
            [pds.identity :as identity]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.repo :as repo]))

(defn- snapshot! [conn settings request]
  (let [account (auth/authenticate! conn settings request {:allow-deactivated? true}) did (:did account)]
    {:account account :repo (repo/state conn did)
     :identity (first (db/query conn "SELECT directory_url, operation_cid, rotation_public FROM plc_identities WHERE did = ?" did))}))

(defn- identity-version [snapshot]
  [(select-keys (:account snapshot) [:did :imported])
   (vec (:public_key (:repo snapshot)))
   (update (:identity snapshot) :rotation_public #(when % (vec %)))])

(defn- valid-did? [settings resolver snapshot]
  (try
    (let [did (get-in snapshot [:account :did])
          plc? (str/starts-with? did "did:plc:")
          audit (when plc? (directory/audit! (:http-client settings) (get-in snapshot [:identity :directory_url]) did))
          document (if plc? (plc/did-document (:data audit)) (identity/resolve-did! resolver did))
          key (identity/signing-key document)]
      (boolean
        (and (= did (get document "id")) (= "ES256" (:algorithm key))
             (= (vec (get-in snapshot [:repo :public_key])) (vec (:public key)))
             (= (:public-url settings) (identity/pds-endpoint document))
             (or (not plc?)
                 (some #{(plc/did-key {:algorithm "ES256K" :public (get-in snapshot [:identity :rotation_public])})}
                       (get-in audit [:data "rotationKeys"]))))))
    ;; Reference behavior exposes resolution failure as validDid=false while
    ;; keeping local transfer counts usable during a directory/DNS outage.
    (catch Exception _ false)))

(defn check! [ds settings resolver request]
  (let [snapshot (db/transact! ds #(snapshot! % settings request))
        valid? (valid-did? settings resolver snapshot)]
    ;; Remote resolution holds no DB locks. Reauthenticate afterward, and never
    ;; apply a verdict about old credentials to changed local identity state.
    (db/transact! ds
      (fn [conn]
        (let [current (snapshot! conn settings request) account (:account current)
              did (:did account) state (:repo current)
              counts (first (db/query conn
                             "SELECT (SELECT count(*) FROM repo_block_owners WHERE did = ?) AS blocks,
                                     (SELECT count(*) FROM records WHERE did = ?) AS records,
                                     (SELECT count(DISTINCT cid) FROM record_blob_refs WHERE did = ?) AS expected,
                                     (SELECT count(*) FROM blobs WHERE did = ?) AS imported,
                                     COALESCE((SELECT jsonb_array_length(preferences) FROM account_preferences WHERE did = ?), 0) AS private"
                             did did did did did))]
          {:activated (= "active" (:status account))
           :validDid (and valid? (= (identity-version snapshot) (identity-version current)))
           :repoCommit (:head state) :repoRev (:rev state)
           :repoBlocks (:blocks counts) :indexedRecords (:records counts)
           :privateStateValues (:private counts) :expectedBlobs (:expected counts) :importedBlobs (:imported counts)})))))
