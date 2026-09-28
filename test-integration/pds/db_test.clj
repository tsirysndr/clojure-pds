(ns pds.db-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.db :as db])
  (:import [java.util UUID]))

(def ^:dynamic *ds*)

(defn postgres?
  "True when the fixture runs against PostgreSQL. Tests that exercise
  PostgreSQL-only mechanics (migration-history replays, ALTER CONSTRAINT
  fault injection, row-lock choreography) guard their bodies with this."
  []
  (not (.startsWith ^String (or (System/getenv "PDS_TEST_DATABASE_URL") "") "jdbc:sqlite:")))

(def ^:dynamic *database*
  "Connection settings for the fixture database, for child processes and
  secondary pools. Recorded by the fixture so callers never reflect on a
  backend-specific datasource class.")

(defn database-env
  "Environment variables pointing another process at the fixture database."
  []
  (cond-> {"PDS_DATABASE_URL" (:url *database*)}
    (:user *database*) (assoc "PDS_DATABASE_USER" (:user *database*))
    (some? (:password *database*)) (assoc "PDS_DATABASE_PASSWORD" (:password *database*))))

(defn export-table-absent?
  "Whether the transaction-local CAR export table is invisible on this
  connection. Temporary-object catalogs differ between the backends."
  [conn]
  (if (postgres?)
    (:absent (first (db/query conn "SELECT to_regclass('pg_temp.pds_car_export_seen') IS NULL AS absent")))
    (empty? (db/query conn "SELECT 1 FROM sqlite_temp_master WHERE type = 'table' AND name = 'pds_car_export_seen'"))))

(defn connection-id
  "Identifier of the physical backend connection, for reuse assertions.
  SQLite has no server process, so every connection reports the same value."
  [conn]
  (if (postgres?)
    (:pid (first (db/query conn "SELECT pg_backend_pid() AS pid")))
    :sqlite))

(defn- isolated-sqlite [f]
  (let [file (java.io.File/createTempFile "pds-test-" ".sqlite3")
        settings {:url (str "jdbc:sqlite:" (.getPath file))}
        ds (db/datasource settings)]
    (try
      (db/migrate! ds)
      (binding [*ds* ds *database* settings] (f))
      (finally
        (doseq [suffix ["" "-wal" "-shm"]]
          (.delete (java.io.File. (str (.getPath file) suffix))))))))

(defn isolated-database [f]
  (let [url (or (System/getenv "PDS_TEST_DATABASE_URL")
                (throw (ex-info "Integration tests require PDS_TEST_DATABASE_URL" {})))]
    (if (.startsWith ^String url "jdbc:sqlite:")
      (isolated-sqlite f)
      (let [settings (assoc (db/settings) :url url)
            admin (db/datasource settings)
            schema (str "test_" (clojure.string/replace (str (UUID/randomUUID)) "-" ""))
            ds (db/datasource (assoc settings :url (str url (if (.contains url "?") "&" "?")
                                                      "currentSchema=" schema)))]
        (with-open [conn (db/connection admin)]
          (db/execute! conn (str "CREATE SCHEMA " schema)))
        (try
          (db/migrate! ds)
          (binding [*ds* ds
                    *database* (assoc settings :url (str url (if (.contains url "?") "&" "?")
                                                        "currentSchema=" schema))]
            (f))
          (finally
            (with-open [conn (db/connection admin)]
              (db/execute! conn (str "DROP SCHEMA " schema " CASCADE")))))))))

(use-fixtures :each isolated-database)

(deftest migrations-and-transactions
  (is (true? (db/migrate! *ds*)))
  (with-open [conn (db/connection *ds*)]
    (is (= (count db/migrations)
           (count (db/query conn "SELECT * FROM schema_migrations")))))
  (is (thrown? Exception
        (db/transact! *ds*
          (fn [conn]
            (db/execute! conn "INSERT INTO repo_blocks(cid, content) VALUES (?, ?)"
                         "rollback" (byte-array [1 2 3]))
            (throw (ex-info "rollback" {}))))))
  (with-open [conn (db/connection *ds*)]
    (is (empty? (db/query conn "SELECT * FROM repo_blocks"))))
  (db/transact! *ds*
    #(db/execute! % "INSERT INTO repo_blocks(cid, content) VALUES (?, ?)"
                  "committed" (byte-array [0 -1 42])))
  (with-open [conn (db/connection *ds*)]
    (is (= [0 -1 42]
           (vec (:content (first (db/query conn "SELECT content FROM repo_blocks")))))))
  (db/transact! *ds* #(db/execute! % "UPDATE schema_migrations SET checksum = 'changed'"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"modified" (db/migrate! *ds*))))

(deftest existing-blob-migration
  (let [all-migrations db/migrations]
    (with-redefs [db/migrations (vec (take 4 all-migrations))]
      (isolated-database
       (fn []
         (db/transact! *ds*
           (fn [conn]
             (db/execute! conn "INSERT INTO accounts(did, handle, email, password_hash) VALUES ('did:web:old.test', 'old.test', 'old@example.com', 'unused')")
             (db/execute! conn "INSERT INTO blobs(did, cid, mime_type, content) VALUES ('did:web:old.test', 'old-cid', 'image/png', ?)"
                          (byte-array [1 2 -1]))))
         (with-redefs [db/migrations all-migrations] (db/migrate! *ds*))
         (with-open [conn (db/connection *ds*)]
           (let [row (first (db/query conn "SELECT * FROM blobs"))]
             (is (= 3 (:size row)))
             (is (= "postgres" (:storage_backend row)))
             (is (= [1 2 -1] (vec (:content row))))
             (is (nil? (:object_key row))))))))))
