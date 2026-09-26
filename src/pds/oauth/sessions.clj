(ns pds.oauth.sessions
  "Owner session management. Callers authenticate the owner and hold its account
  lock before invoking these operations; no credentials or token hashes leave here."
  (:require [pds.db :as db]
            [pds.errors :as errors]
            [pds.oauth.dpop :as dpop])
  (:import [java.time Instant]))

(def page-size 20)
(defn- transaction! [conn]
  (when (.getAutoCommit ^java.sql.Connection conn) (throw (ex-info "Session management requires a transaction" {}))))
(defn- id! [id]
  (when-not (and (string? id) (re-matches #"[A-Za-z0-9_-]{43}" id))
    (errors/invalid! "Invalid OAuth session identifier"))
  id)
(defn list!
  [conn did cursor]
  (transaction! conn)
  (when cursor (id! cursor))
  (let [rows (db/query conn "SELECT s.session_id, s.client_id, s.created_at, s.expires_at,
                                   s.snapshot->'parameters'->>'scope' AS scope
                            FROM oauth_sessions s JOIN accounts a ON a.did = s.did
                            WHERE s.did = ? AND s.account_epoch = a.oauth_epoch AND a.status = 'active'
                              AND s.revoked_at IS NULL AND s.expires_at > ?
                              AND (?::text IS NULL OR s.session_id COLLATE \"C\" > ? COLLATE \"C\")
                            ORDER BY s.session_id COLLATE \"C\" LIMIT ?"
                       did (Instant/ofEpochSecond (dpop/now)) cursor cursor (inc page-size))
        page (vec (take page-size rows))]
    (cond-> {:items (mapv (fn [row]
                           {:id (:session_id row) :client-id (:client_id row) :scope (:scope row)
                            :created-at (str (.toInstant ^java.sql.Timestamp (:created_at row)))
                            :expires-at (str (.toInstant ^java.sql.Timestamp (:expires_at row)))}) page)
             :first-page (nil? cursor)}
      (> (count rows) page-size) (assoc :cursor (:session_id (last page))))))
(defn revoke-owner! [conn did id]
  (transaction! conn)
  (id! id)
  ;; Unknown IDs and IDs owned by someone else have the same result. The
  ;; account predicate is mandatory even when the caller already holds its lock.
  (db/execute! conn "UPDATE oauth_sessions SET revoked_at = ?, revoke_reason = 'owner_revoked'
                    WHERE did = ? AND session_id = ? AND revoked_at IS NULL"
               (Instant/ofEpochSecond (dpop/now)) did id)
  {:revoked true})
