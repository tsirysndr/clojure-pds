(ns pds.db
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pds.db.sqlite :as sqlite])
  (:import [com.zaxxer.hikari HikariDataSource]
           [java.sql Connection Timestamp]
           [java.security MessageDigest]
           [java.util HexFormat]
           [javax.sql DataSource]
           [org.postgresql.ds PGSimpleDataSource]))

(defn settings
  ([] (settings (System/getenv)))
  ([env]
   (let [configured? (some #(contains? env %) ["PDS_DATABASE_URL" "PDS_DATABASE_USER" "PDS_DATABASE_PASSWORD"])
         url (get env "PDS_DATABASE_URL"
                  (if configured?
                    "jdbc:postgresql://127.0.0.1:5432/clojure_pds"
                    ;; No PostgreSQL connection is configured: fall back to a
                    ;; single-file SQLite database.
                    (str "jdbc:sqlite:" (get env "PDS_SQLITE_PATH" "data/clojure-pds.sqlite3"))))]
     (when-not (and (string? url) (or (str/starts-with? url "jdbc:postgresql://") (sqlite/sqlite-url? url)))
       (throw (ex-info "PDS_DATABASE_URL must be a PostgreSQL or SQLite JDBC URL" {})))
     {:url url :user (get env "PDS_DATABASE_USER" "pds")
      :password (get env "PDS_DATABASE_PASSWORD" "")})))

(defn pool-settings
  ([] (pool-settings (System/getenv)))
  ([env]
   (let [read-setting (fn [key default minimum maximum]
                        (let [n (try (Long/parseLong (get env key (str default)))
                                     (catch Exception _ 0))]
                          (when-not (<= minimum n maximum)
                            (throw (ex-info (str key " must be between " minimum " and " maximum) {})))
                          n))]
     {:maximum-size (read-setting "PDS_DB_POOL_SIZE" 20 1 256)
      :timeout-ms (read-setting "PDS_DB_POOL_TIMEOUT_MS" 5000 500 60000)})))

(defn datasource
  "Unpooled datasource for short-lived tools and isolated test fixtures."
  [{:keys [url user password]}]
  (if (sqlite/sqlite-url? url)
    (sqlite/datasource url)
    (doto (PGSimpleDataSource.)
      (.setURL url) (.setUser user) (.setPassword password)
      (.setConnectTimeout 5) (.setSocketTimeout 30)
      (.setTcpKeepAlive true)
      (.setApplicationName "clojure-pds"))))

(defn connection ^Connection [^DataSource ds] (.getConnection ds))

(defn open-pool!
  "Open and verify an owned pool. Call close after all database users stop."
  ^HikariDataSource [database-settings {:keys [maximum-size timeout-ms]}]
  (let [pool (doto (HikariDataSource.)
               (.setDataSource (datasource database-settings))
               (.setMaximumPoolSize maximum-size)
               (.setMinimumIdle 0)
               (.setConnectionTimeout timeout-ms)
               (.setValidationTimeout (min 2000 (quot timeout-ms 2)))
               (.setInitializationFailTimeout 1)
               (.setAutoCommit true)
               (.setTransactionIsolation (if (sqlite/sqlite-url? (:url database-settings))
                                           "TRANSACTION_SERIALIZABLE" "TRANSACTION_READ_COMMITTED")))]
    (try
      (with-open [_ (connection pool)] pool)
      (catch Throwable _
        (.close pool)
        ;; Driver exceptions can include the JDBC URL or credentials.
        (throw (ex-info "Unable to connect to the configured database" {}))))))

(defn- bind! [stmt params sqlite?]
  (doseq [[i value] (map-indexed vector params)]
    (.setObject ^java.sql.PreparedStatement stmt (inc i)
                (if sqlite?
                  (sqlite/bind-value value)
                  (if (instance? java.time.Instant value) (Timestamp/from value) value))))
  stmt)

(defn- read-rows [^java.sql.ResultSet rs sqlite?]
  (let [metadata (.getMetaData rs)
        indexes (range 1 (inc (.getColumnCount metadata)))
        columns (mapv #(keyword (.getColumnLabel metadata %)) indexes)
        types (when sqlite? (mapv #(.getColumnTypeName metadata (int %)) indexes))]
    (loop [rows []]
      (if (.next rs)
        (recur (conj rows (into {} (map-indexed
                                    (fn [i k]
                                      (let [value (.getObject rs (int (inc i)))]
                                        [k (if sqlite? (sqlite/coerce-read (nth types i) value) value)]))
                                    columns))))
        rows))))

(defn execute! [^Connection conn sql & params]
  (if-not (sqlite/sqlite-connection? conn)
    (with-open [stmt (bind! (.prepareStatement conn sql) params false)]
      (.executeUpdate ^java.sql.PreparedStatement stmt))
    (let [translated (sqlite/translate sql)]
      (cond
        (sqlite/noop? translated) 0
        (vector? translated) (with-open [stmt (.createStatement conn)]
                               (reduce (fn [_ statement] (.executeUpdate stmt ^String statement)) 0 translated))
        ;; execute + getUpdateCount tolerates statements the driver classifies
        ;; as result-returning (e.g. upserts with conditional DO UPDATE).
        :else (with-open [stmt (bind! (.prepareStatement conn translated) params true)]
                (.execute ^java.sql.PreparedStatement stmt)
                (max 0 (.getUpdateCount ^java.sql.PreparedStatement stmt)))))))

(defn query [^Connection conn sql & params]
  (if-not (sqlite/sqlite-connection? conn)
    (with-open [stmt (bind! (.prepareStatement conn sql) params false)
                rs (.executeQuery ^java.sql.PreparedStatement stmt)]
      (read-rows rs false))
    (let [translated (sqlite/translate sql)]
      (if (sqlite/noop? translated)
        []
        ;; execute + getResultSet also covers DELETE/INSERT ... RETURNING.
        (with-open [stmt (bind! (.prepareStatement conn translated) params true)]
          (.execute ^java.sql.PreparedStatement stmt)
          (if-let [rs (.getResultSet ^java.sql.PreparedStatement stmt)]
            (with-open [rs rs] (read-rows rs true))
            []))))))

(defn unique-violation?
  "Backend-neutral duplicate-key detection for INSERT conflict handling."
  [^java.sql.SQLException e]
  (or (= "23505" (.getSQLState e))
      (boolean (re-find #"(?i)UNIQUE constraint failed|SQLITE_CONSTRAINT" (str (.getMessage e))))))

(defn- clear-temporary-state!
  "SQLite temporary tables live for the connection, while their PostgreSQL
  originals are ON COMMIT DROP. Pooled connections must not carry one
  transaction's temporary rows into the next."
  [^Connection conn]
  (when (sqlite/sqlite-connection? conn)
    (try
      (doseq [name (map :name (query conn "SELECT name FROM sqlite_temp_master WHERE type = 'table'"))]
        (execute! conn (str "DROP TABLE IF EXISTS temp.\"" name "\"")))
      (.commit conn)
      (catch Throwable _ nil))))

(defn transact!
  "Run f with an owned connection. Exceptions roll back all changes."
  [ds f]
  (with-open [conn (connection ds)]
    (.setAutoCommit conn false)
    (try
      (let [result (f conn)] (.commit conn) result)
      (catch Throwable t
        (try (.rollback conn) (catch Throwable rollback (.addSuppressed t rollback)))
        (throw t))
      (finally (clear-temporary-state! conn)))))

(def migrations ["001-storage.sql" "002-email.sql" "003-sessions.sql" "004-repo-events.sql"
                 "005-blob-storage.sql" "006-app-passwords.sql" "007-account-lifecycle.sql"
                 "008-blob-deletion.sql" "009-email-security.sql" "010-invites.sql"
                 "011-account-takedowns.sql" "012-repository-block-ownership.sql" "013-event-payloads.sql"
                 "014-plc-provisioning.sql" "015-handle-updates.sql" "016-plc-signing-tokens.sql"
                 "017-plc-submissions.sql" "018-service-token-replay.sql" "019-account-imports.sql"
                 "020-record-blob-references.sql" "021-record-revisions.sql" "022-blob-lifecycle.sql"
                 "023-empty-blobs.sql" "024-oauth-dpop.sql" "025-oauth-client-assertions.sql" "026-oauth-par.sql" "027-oauth-interactions.sql" "028-totp.sql" "029-passkeys.sql" "030-browser-sessions.sql" "031-oauth-tokens.sql" "032-oauth-session-list.sql" "033-oauth-permission-set-cache.sql" "034-oauth-permission-snapshots.sql" "035-relay-announcements.sql" "036-private-preferences.sql" "037-content-takedowns.sql" "038-plc-key-rotation.sql" "039-signing-key-rotation.sql" "040-master-key-lifecycle.sql" "041-authenticator-recovery.sql" "042-plc-reconciliation.sql" "043-plc-recovery-keys.sql" "044-reserved-signing-keys.sql" "045-records-by-collection.sql"])

(def sqlite-migrations
  ;; A fresh backend gets one consolidated baseline; future changes append
  ;; matching numbered files to both dialect directories.
  ["001-baseline.sql"])

(defn migrate! [ds]
  (transact!
   ds
   (fn [conn]
     ;; Serialize startup across processes, and release the lock on rollback
     ;; too. On SQLite the immediate write transaction is the lock.
     (query conn "SELECT pg_advisory_xact_lock(731946281)")
     (execute! conn "CREATE TABLE IF NOT EXISTS schema_migrations
                      (name text PRIMARY KEY, checksum text NOT NULL,
                       applied_at timestamptz NOT NULL DEFAULT now())")
     (doseq [name (if (sqlite/sqlite-connection? conn) sqlite-migrations migrations)]
       (let [sql (slurp (io/resource (str (if (sqlite/sqlite-connection? conn) "migrations-sqlite/" "migrations/") name))
                        :encoding "UTF-8")
             checksum (.formatHex (HexFormat/of)
                                  (.digest (MessageDigest/getInstance "SHA-256")
                                           (.getBytes sql "UTF-8")))
             applied (first (query conn "SELECT checksum FROM schema_migrations WHERE name = ?" name))]
         (if applied
           (when-not (= checksum (:checksum applied))
             (throw (ex-info "An applied database migration was modified" {:migration name})))
           (do (if (sqlite/sqlite-connection? conn)
                 ;; SQLite scripts are already in dialect; run them verbatim,
                 ;; statement by statement.
                 (with-open [stmt (.createStatement conn)]
                   (doseq [statement (sqlite/script-statements sql)]
                     (.executeUpdate stmt ^String statement)))
                 (execute! conn sql))
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
  (let [database (settings)]
    (migrate! (datasource database))
    (println (if (sqlite/sqlite-url? (:url database)) "SQLite" "PostgreSQL") "migrations applied")))
