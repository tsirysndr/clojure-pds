(ns pds.preferences
  "Private Bluesky preferences. Callers authenticate and lock the account.
  Restricted writes replace only the preferences that the caller can read."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax])
  (:import [java.time LocalDate OffsetDateTime ZoneOffset]
           [java.time.temporal ChronoUnit]))

(def personal "app.bsky.actor.defs#personalDetailsPref")
(def declared-age "app.bsky.actor.defs#declaredAgePref")
(defn today [] (LocalDate/now ZoneOffset/UTC))
(defn full? [account] (= "com.atproto.access" (:access-scope account)))
(defn allowed? [account preference] (or (full? account) (not= personal (get preference "$type"))))
(defn- stored [conn did]
  (if-let [row (first (db/query conn "SELECT preferences::text FROM account_preferences WHERE did = ?" did))]
    (json/read-str (:preferences row)) []))
(defn read! [conn account]
  (let [preferences (stored conn (:did account))
        birth-date (get (first (filter #(= personal (get % "$type")) preferences)) "birthDate")
        age (when birth-date
              (.between ChronoUnit/YEARS (.toLocalDate (.atZoneSameInstant (OffsetDateTime/parse birth-date) ZoneOffset/UTC)) (today)))
        preferences (cond-> preferences
                      age (conj {"$type" declared-age "isOverAge13" (>= age 13)
                                 "isOverAge16" (>= age 16) "isOverAge18" (>= age 18)}))]
    {:preferences (filterv #(allowed? account %) preferences)}))
(defn put! [conn account preferences]
  (when (.getAutoCommit ^java.sql.Connection conn) (throw (ex-info "Preference writes require an account-locked transaction" {})))
  (when-not (and (vector? preferences) (<= (count preferences) 1000))
    (errors/invalid! "Expected at most 1000 preferences"))
  (doseq [preference preferences :let [type (get preference "$type")]]
    (when-not (and (map? preference) (syntax/type-ref? type) (str/starts-with? type "app.bsky."))
      (errors/invalid! "Preferences must have an app.bsky namespace $type"))
    (when-not (allowed? account preference) (errors/raise! 403 "AuthRequired" "Personal details require a primary-password session")))
  (let [retained (remove #(allowed? account %) (stored conn (:did account)))
        values (into (vec retained) (remove #(= declared-age (get % "$type")) preferences))
        serialized (json/write-str values)]
    (when (or (> (count values) 1000) (> (alength (codec/utf8 serialized)) 1048576))
      (errors/raise! 413 "PayloadTooLarge" "Stored preferences exceed the limit"))
    (db/execute! conn "INSERT INTO account_preferences(did, preferences) VALUES (?, ?::jsonb)
                       ON CONFLICT (did) DO UPDATE SET preferences = excluded.preferences" (:did account) serialized)
    nil))
