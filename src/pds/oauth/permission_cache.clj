(ns pds.oauth.permission-cache
  "Shared verified permission-set cache. Short database leases fence concurrent
  refreshes; all DNS/HTTPS work happens after the lease transaction commits."
  (:require [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.lexicon-resolver :as lexicon]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.http :as http]
            [pds.oauth.permission-sets :as sets]
            [pds.protocol.codec :as codec]))

(def stale-seconds 86400)
(def expiry-seconds (* 90 86400))
(def retry-seconds 60)
(def lease-seconds 30)
(def minimum-retention 1800)
(def capacity 1024)

(defn cache [ds resolver]
  {:ds ds :resolve #(lexicon/resolve! resolver %) :clock dpop/now})
(defn- unavailable! [] (http/fail! "temporarily_unavailable" "Permission set resolution is unavailable"))
(defn- row
  ([conn nsid] (row conn nsid false))
  ([conn nsid lock?]
   (first (db/query conn (str "SELECT * FROM oauth_permission_set_cache WHERE nsid = ?"
                             (when lock? " FOR UPDATE")) nsid))))
(defn- entry [row]
  (when (:document row)
    {:nsid (:nsid row) :schema (codec/decode (:document row) 1000000)
     :did (:did row) :cid (:cid row) :head (:head row) :rev (:rev row) :fetched-at (:fetched_at row)}))
(defn- usable [row timestamp fallback]
  (let [value (entry row)
        regressed? (and value fallback (= (:did value) (:did fallback))
                        (or (neg? (compare (:rev value) (:rev fallback)))
                            (and (= (:rev value) (:rev fallback)) (not= (:head value) (:head fallback)))))]
    (or (when (and value (not regressed?) (< timestamp (+ (:fetched-at value) expiry-seconds))) value)
        ;; Only the caller's persisted session snapshot may outlive cache expiry
        ;; or eviction. New authorization requests never supply this fallback.
        fallback)))

(defn- claim! [{:keys [ds]} nsid timestamp fallback]
  (db/transact! ds
    (fn [conn]
      (db/execute! conn "SET LOCAL statement_timeout = '5s'")
      ;; Briefly serialize cache admission and eviction across instances. This
      ;; never holds a network request, nor any account/session lock.
      (db/query conn "SELECT pg_advisory_xact_lock(731946282)")
      (let [current (row conn nsid true)]
        (if (and current (or (> (:lease_until current) timestamp) (> (:next_attempt_at current) timestamp)))
          {:value (usable current timestamp fallback)}
          (do
            (when-not current
              (when (>= (:n (first (db/query conn "SELECT count(*) AS n FROM oauth_permission_set_cache"))) capacity)
                ;; Preserve at least 30 minutes of fresh cached data. Evicted
                ;; sessions retain their own authenticated schema snapshots.
                (db/execute! conn "DELETE FROM oauth_permission_set_cache WHERE nsid IN
                                    (SELECT nsid FROM oauth_permission_set_cache
                                     WHERE lease_until <= ? AND next_attempt_at <= ? AND (fetched_at IS NULL OR fetched_at <= ?)
                                     ORDER BY next_attempt_at, nsid LIMIT 1)"
                             timestamp timestamp (- timestamp minimum-retention)))
              (when (>= (:n (first (db/query conn "SELECT count(*) AS n FROM oauth_permission_set_cache"))) capacity)
                (unavailable!)))
            (let [lease (crypto/token)]
              (db/execute! conn "INSERT INTO oauth_permission_set_cache(nsid, next_attempt_at, lease_id, lease_until)
                                 VALUES (?, ?, ?, ?) ON CONFLICT (nsid) DO UPDATE SET lease_id = EXCLUDED.lease_id, lease_until = EXCLUDED.lease_until"
                           nsid timestamp lease (+ timestamp lease-seconds))
              {:lease lease :previous (entry current)})))))))

(defn- publish! [{:keys [ds]} nsid lease timestamp resolved]
  (db/transact! ds
    (fn [conn]
      (db/execute! conn "SET LOCAL statement_timeout = '5s'")
      (when (pos? (db/execute! conn "UPDATE oauth_permission_set_cache SET document = ?, did = ?, cid = ?, head = ?, rev = ?,
                                     fetched_at = ?, next_attempt_at = ?, lease_id = NULL, lease_until = 0
                                     WHERE nsid = ? AND lease_id = ?"
                              (codec/encode (:schema resolved)) (:did resolved) (:cid resolved) (:head resolved) (:rev resolved)
                              timestamp (+ timestamp stale-seconds) nsid lease))
        (assoc resolved :fetched-at timestamp)))))
(defn- failed! [{:keys [ds]} nsid lease timestamp]
  (db/transact! ds
    (fn [conn]
      (db/execute! conn "SET LOCAL statement_timeout = '5s'")
      (db/execute! conn "UPDATE oauth_permission_set_cache SET next_attempt_at = ?, lease_id = NULL, lease_until = 0
                        WHERE nsid = ? AND lease_id = ?" (+ timestamp retry-seconds) nsid lease))))

(defn resolve!
  "Use a verified schema for a new authorization, or refresh an existing session
  with its previous authenticated snapshot as fallback. New sessions have a
  90-day expiry; a failed refresh never silently extends the cached fetch time.
  An active cold lease returns temporary unavailability; clients can retry."
  ([cache nsid] (resolve! cache nsid nil))
  ([{:keys [ds resolve clock] :as cache} nsid fallback]
   (lexicon/authority-name nsid)
   (when fallback
     (when-not (= nsid (:nsid fallback)) (unavailable!))
     (sets/validate! nsid (:schema fallback)))
   (let [timestamp (clock)
         current (with-open [conn (db/connection ds)] (row conn nsid))]
     (if (and current (> (:next_attempt_at current) timestamp))
       (or (usable current timestamp fallback) (unavailable!))
       (let [{:keys [lease previous value]}
             (try (claim! cache nsid timestamp fallback)
                  (catch clojure.lang.ExceptionInfo e
                    (if (and fallback (= "temporarily_unavailable" (:oauth-error (ex-data e))))
                      {:value fallback} (throw e))))]
         (if-not lease
           (or value (unavailable!))
           (let [resolved (try
                            (let [result (resolve nsid)]
                              (when-not (= nsid (:nsid result)) (unavailable!))
                              (sets/validate! nsid (:schema result))
                              ;; A stale/forked response cannot roll back a cached
                              ;; publisher revision. DNS-authorized DID migration
                              ;; can legitimately start another revision sequence.
                              (doseq [known (remove nil? [previous fallback])]
                                (when (and (= (:did known) (:did result))
                                           (or (neg? (compare (:rev result) (:rev known)))
                                               (and (= (:rev result) (:rev known)) (not= (:head result) (:head known)))))
                                  (unavailable!)))
                              result)
                            (catch Exception _ nil))]
             (if resolved
               (or (publish! cache nsid lease (clock) resolved)
                   ;; A superseded lease must not return its own obsolete result.
                   (with-open [conn (db/connection ds)] (usable (row conn nsid) (clock) fallback))
                   (unavailable!))
               (do (failed! cache nsid lease (clock))
                   (or (with-open [conn (db/connection ds)] (usable (row conn nsid) (clock) fallback))
                       (unavailable!)))))))))))
