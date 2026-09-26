(ns pds.db
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.sql Connection Timestamp]
           [java.security MessageDigest]
           [java.util HexFormat]
           [javax.sql DataSource]
           [org.postgresql.ds PGSimpleDataSource]))

(defn settings
  ([] (settings (System/getenv)))
  ([env]
   (let [url (get env "PDS_DATABASE_URL" "jdbc:postgresql://127.0.0.1:5432/clojure_pds")]
     (when-not (and (string? url) (str/starts-with? url "jdbc:postgresql://"))
       (throw (ex-info "PDS_DATABASE_URL must be a PostgreSQL JDBC URL" {})))
     {:url url :user (get env "PDS_DATABASE_USER" "pds")
      :password (get env "PDS_DATABASE_PASSWORD" "")})))

(defn datasource [{:keys [url user password]}]
  (doto (PGSimpleDataSource.)
    (.setURL url) (.setUser user) (.setPassword password)
    (.setConnectTimeout 5) (.setSocketTimeout 30)
    (.setApplicationName "clojure-pds")))

(defn connection ^Connection [^DataSource ds] (.getConnection ds))

(defn- bind! [stmt params]
  (doseq [[i value] (map-indexed vector params)]
    (.setObject ^java.sql.PreparedStatement stmt (inc i)
                (if (instance? java.time.Instant value) (Timestamp/from value) value)))
  stmt)

(defn execute! [^Connection conn sql & params]
  (with-open [stmt (bind! (.prepareStatement conn sql) params)]
    (.executeUpdate ^java.sql.PreparedStatement stmt)))

(defn query [^Connection conn sql & params]
  (with-open [stmt (bind! (.prepareStatement conn sql) params)
              rs (.executeQuery ^java.sql.PreparedStatement stmt)]
    (let [metadata (.getMetaData rs)
          columns (mapv #(keyword (.getColumnLabel metadata %))
                        (range 1 (inc (.getColumnCount metadata))))]
      (loop [rows []]
        (if (.next rs)
          (recur (conj rows (into {} (map-indexed
                                     (fn [i k] [k (.getObject rs (inc i))]) columns))))
          rows)))))

(defn transact!
  "Run f with an owned connection. Exceptions roll back all changes."
  [ds f]
  (with-open [conn (connection ds)]
    (.setAutoCommit conn false)
    (try
      (let [result (f conn)] (.commit conn) result)
      (catch Throwable t (.rollback conn) (throw t)))))

(def migrations ["001-storage.sql" "002-email.sql" "003-sessions.sql" "004-repo-events.sql"
                 "005-blob-storage.sql" "006-app-passwords.sql" "007-account-lifecycle.sql"
                 "008-blob-deletion.sql" "009-email-security.sql" "010-invites.sql"
                 "011-account-takedowns.sql" "012-repository-block-ownership.sql" "013-event-payloads.sql"
                 "014-plc-provisioning.sql" "015-handle-updates.sql" "016-plc-signing-tokens.sql"
                 "017-plc-submissions.sql" "018-service-token-replay.sql" "019-account-imports.sql"
                 "020-record-blob-references.sql" "021-record-revisions.sql" "022-blob-lifecycle.sql"
                 "023-empty-blobs.sql" "024-oauth-dpop.sql" "025-oauth-client-assertions.sql" "026-oauth-par.sql" "027-oauth-interactions.sql" "028-totp.sql" "029-passkeys.sql" "030-browser-sessions.sql" "031-oauth-tokens.sql" "032-oauth-session-list.sql"])

(defn migrate! [ds]
  (transact!
   ds
   (fn [conn]
     ;; Serialize startup across processes, and release the lock on rollback too.
     (query conn "SELECT pg_advisory_xact_lock(731946281)")
     (execute! conn "CREATE TABLE IF NOT EXISTS schema_migrations
                      (name text PRIMARY KEY, checksum text NOT NULL,
                       applied_at timestamptz NOT NULL DEFAULT now())")
     (doseq [name migrations]
       (let [sql (slurp (io/resource (str "migrations/" name)) :encoding "UTF-8")
             checksum (.formatHex (HexFormat/of)
                                  (.digest (MessageDigest/getInstance "SHA-256")
                                           (.getBytes sql "UTF-8")))
             applied (first (query conn "SELECT checksum FROM schema_migrations WHERE name = ?" name))]
         (if applied
           (when-not (= checksum (:checksum applied))
             (throw (ex-info "An applied database migration was modified" {:migration name})))
           (do (execute! conn sql)
               ;; Data backfill shares the SQL migration's transaction and lock.
               (when (= name "012-repository-block-ownership.sql")
                 ((requiring-resolve 'pds.block-index/backfill!) conn))
               (when (= name "013-event-payloads.sql")
                 ((requiring-resolve 'pds.events/backfill!) conn))
               (when (#{"020-record-blob-references.sql" "023-empty-blobs.sql"} name)
                 ((requiring-resolve 'pds.blob-refs/backfill!) conn))
               (execute! conn "INSERT INTO schema_migrations(name, checksum) VALUES (?, ?)"
                         name checksum)))))
     true)))

(defn -main [& _]
  (migrate! (datasource (settings)))
  (println "PostgreSQL migrations applied"))
