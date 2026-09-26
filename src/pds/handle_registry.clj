(ns pds.handle-registry
  (:require [pds.db :as db]
            [pds.errors :as errors]))

(defn reserve! [conn did handle]
  (db/execute! conn "INSERT INTO handle_reservations(handle, did, permanent) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
               handle did (= did (str "did:web:" handle)))
  (when-not (= did (:did (first (db/query conn "SELECT did FROM handle_reservations WHERE handle = ?" handle))))
    (errors/raise! 400 "HandleNotAvailable" "Handle is reserved by another account")))

(defn release! [conn did handle]
  (db/execute! conn "DELETE FROM handle_reservations WHERE did = ? AND handle = ? AND NOT permanent" did handle))
