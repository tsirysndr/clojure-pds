(ns pds.db-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.db :as db])
  (:import [java.util UUID]))

(def ^:dynamic *ds*)

(defn isolated-database [f]
  (let [url (or (System/getenv "PDS_TEST_DATABASE_URL")
                (throw (ex-info "Integration tests require PDS_TEST_DATABASE_URL" {})))
        settings (assoc (db/settings) :url url)
        admin (db/datasource settings)
        schema (str "test_" (clojure.string/replace (str (UUID/randomUUID)) "-" ""))
        ds (db/datasource (assoc settings :url (str url (if (.contains url "?") "&" "?")
                                                  "currentSchema=" schema)))]
    (with-open [conn (db/connection admin)]
      (db/execute! conn (str "CREATE SCHEMA " schema)))
    (try
      (db/migrate! ds)
      (binding [*ds* ds] (f))
      (finally
        (with-open [conn (db/connection admin)]
          (db/execute! conn (str "DROP SCHEMA " schema " CASCADE")))))))

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
