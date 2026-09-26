(ns pds.events
  (:require [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec])
  (:import [java.time Instant]))

(defn append! [conn did event-type payload]
  ;; Same lock as repository commits: sequence order agrees with commit order.
  (db/query conn "SELECT pg_advisory_xact_lock(731946282)")
  (db/execute! conn "INSERT INTO repo_events(did, event_type, payload) VALUES (?, ?, ?)"
               did event-type (codec/encode (assoc payload "time" (str (Instant/now))))))

(defn account! [conn did status]
  (append! conn did "account"
           (cond-> {"did" did "active" (= status "active")}
             (not= status "active") (assoc "status" (if (= status "taken_down") "takendown" status)))))

(defn commit! [conn did head rev since previous-data ops blocks]
  (let [slice (car/encode head blocks)]
    (when (> (alength slice) 2000000)
      (errors/raise! 413 "PayloadTooLarge" "Commit proof exceeds 2,000,000 bytes; split the write batch"))
    (let [payload (cond-> {"rebase" false "tooBig" false "repo" did "commit" (codec/link head)
                           "rev" rev "since" since "blocks" slice "ops" (vec ops) "blobs" []
                           "time" (str (Instant/now))}
                    previous-data (assoc "prevData" previous-data))]
      (db/query conn "SELECT pg_advisory_xact_lock(731946282)")
      (db/execute! conn "INSERT INTO repo_events(did, rev, commit_cid, payload) VALUES (?, ?, ?, ?)"
                   did rev head (codec/encode payload)))))

(defn sync! [conn did]
  (let [row (first (db/query conn "SELECT r.head, r.rev, b.content FROM repositories r JOIN repo_blocks b ON b.cid = r.head WHERE r.did = ?" did))]
    (append! conn did "sync" {"did" did "rev" (:rev row) "blocks" (car/encode (:head row) {(:head row) (:content row)})})))

(defn backfill! [conn]
  ;; Old commits did not retain mutation operations. Convert those events to
  ;; truthful sync checkpoints at their original sequence numbers.
  (doseq [row (db/query conn "SELECT e.seq, e.did, e.rev, e.commit_cid, e.created_at, b.content
                              FROM repo_events e JOIN repo_blocks b ON b.cid = e.commit_cid
                              WHERE e.event_type = 'commit' AND e.payload IS NULL ORDER BY e.seq")]
    (db/execute! conn "UPDATE repo_events SET event_type = 'sync', payload = ? WHERE seq = ?"
                 (codec/encode {"did" (:did row) "rev" (:rev row)
                                "time" (str (.toInstant ^java.sql.Timestamp (:created_at row)))
                                "blocks" (car/encode (:commit_cid row) {(:commit_cid row) (:content row)})}) (:seq row))))
