(ns pds.moderation
  (:require [clojure.string :as str]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.events :as events]
            [pds.invites :as invites]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax]))

(defn account! [conn did]
  (when-not (syntax/did? did) (errors/invalid! "Invalid DID"))
  (or (first (db/query conn "SELECT * FROM accounts WHERE did = ? AND status IN ('active', 'deactivated', 'taken_down') FOR UPDATE" did))
      (errors/raise! 400 "AccountNotFound" "Account was not found")))

(defn status-view [account]
  {:subject {:$type "com.atproto.admin.defs#repoRef" :did (:did account)}
   :takedown (cond-> {:applied (= "taken_down" (:status account))}
               (:takedown_ref account) (assoc :ref (:takedown_ref account)))
   :deactivated {:applied (or (= "deactivated" (:status account))
                              (and (= "taken_down" (:status account)) (= "deactivated" (:status_before_takedown account))))}})

(defn- cid! [cid]
  (try (codec/cid-bytes cid)
       (catch Exception _ (errors/invalid! "Invalid subject CID"))))

(defn- content-target! [subject]
  (case (get subject "$type")
    "com.atproto.repo.strongRef"
    (let [uri (get subject "uri")
          _ (when-not (syntax/at-uri? uri) (errors/invalid! "Invalid record URI"))
          [did collection rkey] (str/split (subs uri 5) #"/" -1)]
      (when-not (and (syntax/did? did) collection rkey)
        (errors/invalid! "Record moderation requires a DID-based record URI"))
      {:did did :table "records" :where "did = ? AND collection = ? AND rkey = ?"
       :params [did collection rkey]})
    "com.atproto.admin.defs#repoBlobRef"
    (let [did (get subject "did") cid (get subject "cid")]
      (when-not (syntax/did? did) (errors/invalid! "Invalid DID"))
      (cid! cid)
      {:did did :table "blobs" :where "did = ? AND cid = ?" :params [did cid]})
    (errors/invalid! "Unsupported moderation subject")))

(defn- content-row! [conn {:keys [did table where params]}]
  ;; Account lock matches writes, imports, uploads and deletion lock ordering.
  (account! conn did)
  (or (first (apply db/query conn (str "SELECT cid, takedown_ref FROM " table " WHERE " where " FOR UPDATE") params))
      (errors/raise! 400 "NotFound" "Subject was not found")))

(defn- takedown-view [reference]
  (cond-> {:applied (some? reference)} (some? reference) (assoc :ref reference)))

(defn get-status [conn params]
  (if-let [subject (cond
                    (contains? params "blob") {"$type" "com.atproto.admin.defs#repoBlobRef" "did" (get params "did") "cid" (get params "blob")}
                    (contains? params "uri") {"$type" "com.atproto.repo.strongRef" "uri" (get params "uri")})]
    (let [row (content-row! conn (content-target! subject))]
      {:subject (assoc subject "cid" (:cid row)) :takedown (takedown-view (:takedown_ref row))})
    (status-view (account! conn (get params "did")))))

(defn status-attr! [body field]
  (when (contains? body field)
    (let [attr (get body field)]
      (when-not (and (map? attr) (boolean? (get attr "applied"))
                     (or (not (contains? attr "ref"))
                         (and (string? (get attr "ref")) (<= (count (get attr "ref")) 10000))))
        (errors/invalid! (str "Invalid " field " status")))
      attr)))

(defn- update-account-status! [conn body]
  (let [subject (get body "subject")]
    (let [account (account! conn (get subject "did"))
          takedown (status-attr! body "takedown")
          deactivated (status-attr! body "deactivated")
          current (status-view account)
          suspended? (if takedown (get takedown "applied") (get-in current [:takedown :applied]))
          inactive? (if deactivated (get deactivated "applied") (get-in current [:deactivated :applied]))
          base-status (if inactive? "deactivated" "active")
          status (if suspended? "taken_down" base-status)
          reference (when suspended? (if takedown (get takedown "ref") (:takedown_ref account)))]
      (when (and (= "active" base-status) (seq (db/query conn "SELECT 1 FROM account_imports WHERE did = ?" (:did account))))
        (errors/raise! 400 "MigrationIncomplete" "Complete the pending account migration before activation"))
      ;; Account lock is shared with authentication and repository writes. The
      ;; sequencer lock then places the status after any completed writes.
      (db/execute! conn "UPDATE accounts SET status = ?, status_before_takedown = ?, takedown_ref = ?,
                          delete_after = CASE WHEN ? THEN delete_after ELSE NULL END WHERE did = ?"
                   status base-status reference inactive? (:did account))
      (when (not= status (:status account))
        (events/account! conn (:did account) status)
        (when (= status "active") (events/sync! conn (:did account))))
      (dissoc (status-view (assoc account :status status :status_before_takedown base-status :takedown_ref reference)) :deactivated))))

(defn update-status! [conn body]
  (let [subject (get body "subject")]
    (if (= "com.atproto.admin.defs#repoRef" (get subject "$type"))
      (update-account-status! conn body)
      (let [{:keys [table where params] :as target} (content-target! subject)
            _ (when (= "records" table) (cid! (get subject "cid")))
            row (content-row! conn target)
            takedown (status-attr! body "takedown")
            _ (status-attr! body "deactivated")
            reference (if takedown
                        (when (get takedown "applied") (get takedown "ref" (str (java.time.Instant/now))))
                        (:takedown_ref row))]
        ;; Like the reference PDS, a strongRef selects the current record at
        ;; that URI; CID is not a swap condition. No repository commit is made.
        (when takedown
          (apply db/execute! conn (str "UPDATE " table " SET takedown_ref = ? WHERE " where) reference params))
        {:subject subject :takedown (takedown-view reference)}))))

(defn account-info [conn did]
  (let [account (account! conn did)
        invited-by (first (db/query conn "SELECT c.* FROM invite_codes c JOIN invite_uses u ON u.code = c.code WHERE u.used_by = ?" did))]
    (cond-> {:did did :handle (:handle account)
             :indexedAt (str (.toInstant ^java.sql.Timestamp (:created_at account)))
             :invitesDisabled (:invites_disabled account)
             :invites (:codes (invites/account-codes conn did {}))}
      (:email account) (assoc :email (:email account))
      (:invite_note account) (assoc :inviteNote (:invite_note account))
      invited-by (assoc :invitedBy (invites/view conn invited-by)))))
