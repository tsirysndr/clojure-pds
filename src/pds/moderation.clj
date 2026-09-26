(ns pds.moderation
  (:require [pds.db :as db]
            [pds.errors :as errors]
            [pds.events :as events]
            [pds.invites :as invites]
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

(defn get-status [conn params]
  (when (or (contains? params "uri") (contains? params "blob"))
    (errors/invalid! "Record and blob moderation is not implemented"))
  (status-view (account! conn (get params "did"))))

(defn status-attr! [body field]
  (when (contains? body field)
    (let [attr (get body field)]
      (when-not (and (map? attr) (boolean? (get attr "applied"))
                     (or (not (contains? attr "ref"))
                         (and (string? (get attr "ref")) (<= (count (get attr "ref")) 10000))))
        (errors/invalid! (str "Invalid " field " status")))
      attr)))

(defn update-status! [conn body]
  (let [subject (get body "subject")]
    (when-not (= "com.atproto.admin.defs#repoRef" (get subject "$type"))
      (errors/invalid! "Expected an account repoRef; record and blob moderation is not implemented"))
    (let [account (account! conn (get subject "did"))
          takedown (status-attr! body "takedown")
          deactivated (status-attr! body "deactivated")
          current (status-view account)
          suspended? (if takedown (get takedown "applied") (get-in current [:takedown :applied]))
          inactive? (if deactivated (get deactivated "applied") (get-in current [:deactivated :applied]))
          base-status (if inactive? "deactivated" "active")
          status (if suspended? "taken_down" base-status)
          reference (when suspended? (if takedown (get takedown "ref") (:takedown_ref account)))]
      ;; Account lock is shared with authentication and repository writes. The
      ;; sequencer lock then places the status after any completed writes.
      (db/execute! conn "UPDATE accounts SET status = ?, status_before_takedown = ?, takedown_ref = ?,
                          delete_after = CASE WHEN ? THEN delete_after ELSE NULL END WHERE did = ?"
                   status base-status reference inactive? (:did account))
      (when (not= status (:status account))
        (events/account! conn (:did account) status)
        (when (= status "active") (events/sync! conn (:did account))))
      (dissoc (status-view (assoc account :status status :status_before_takedown base-status :takedown_ref reference)) :deactivated))))

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
