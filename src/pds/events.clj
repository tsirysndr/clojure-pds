(ns pds.events
  (:require [pds.db :as db]
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
