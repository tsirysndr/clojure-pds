(ns pds.backup-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.blobs :as blobs]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.firehose-test :as stream]
            [pds.http :as http]
            [pds.moderation :as moderation]
            [pds.preferences :as preferences]
            [pds.protocol.codec :as codec]
            [pds.protocol.repository :as repository]
            [pds.repo :as repo]
            [pds.repo-import-test :as imports]
            [pds.s3 :as s3]
            [pds.s3-test :as s3-test]
            [pds.server-api-test :as api]
            [pds.sync-api-test :as sync-test]
            [pds.websocket-test :as ws])
  (:import [java.net URI]
           [java.net.http HttpClient]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]
           [java.util UUID]
           [java.util.concurrent TimeUnit]
           [software.amazon.awssdk.services.s3 S3Client]
           [software.amazon.awssdk.services.s3.model CreateBucketRequest]))

(defn with-databases [f]
  (let [url (or (System/getenv "PDS_TEST_DATABASE_URL") (throw (ex-info "Test database URL required" {})))
        uri (URI/create (subs url 5))
        _ (when-not (#{"localhost" "127.0.0.1" "[::1]"} (.getHost uri))
            (throw (ex-info "Backup drill requires a loopback disposable PostgreSQL server" {})))
        settings (assoc (db/settings) :url url)
        admin (db/datasource settings)
        prefix (str "backup_" (.replace (str (UUID/randomUUID)) "-" ""))
        names [(str prefix "_source") (str prefix "_target")]
        created (atom [])
        config (fn [name] (assoc settings :url (str "jdbc:postgresql://" (.getRawAuthority uri) "/" name)))
        connection-env (fn [name] {"PGHOST" (.getHost uri) "PGPORT" (str (if (= -1 (.getPort uri)) 5432 (.getPort uri)))
                                   "PGDATABASE" name "PGUSER" (:user settings) "PGPASSWORD" (:password settings)})]
    (try
      (doseq [name names]
        (with-open [c (db/connection admin)] (db/execute! c (str "CREATE DATABASE " name " TEMPLATE template0")))
        (swap! created conj name))
      (f (db/datasource (config (first names))) (db/datasource (config (second names)))
         (connection-env (first names)) (connection-env (second names)))
      (finally
        (doseq [name (reverse @created)]
          (with-open [c (db/connection admin)] (db/execute! c (str "DROP DATABASE " name " WITH (FORCE)"))))))))

(defn run-command [env arguments]
  (let [builder (ProcessBuilder. ^java.util.List arguments)
        child-env (.environment builder)]
    (doseq [key ["PGSERVICE" "PGSERVICEFILE" "PGOPTIONS" "PGHOSTADDR"]] (.remove child-env key))
    (.putAll child-env env)
    (.redirectErrorStream builder true)
    (let [process (.start builder) output (future (slurp (.getInputStream process)))]
      (try
        (when-not (.waitFor process 60 TimeUnit/SECONDS)
          (throw (ex-info "Backup test command timed out" {})))
        {:exit (.exitValue process) :output (deref output 5000 "output timeout")}
        (finally (when (.isAlive process) (.destroyForcibly process)))))))

(defn command [env action folder]
  (run-command env ["python3" "scripts/database-backup.py" action (str folder)]))

(defn snapshot [ds]
  (with-open [c (db/connection ds)]
    (into {} (for [{:keys [tablename]} (db/query c "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")]
               [tablename (mapv :row (db/query c (str "SELECT row_to_json(t)::text AS row FROM " tablename " t ORDER BY row")))]))))

(defn seed! [ds settings]
  (db/migrate! ds)
  (let [account (accounts/create! ds settings {"handle" "restored.example.com" "email" "restored@example.com" "password" "backup-password"})
        did (:did account) content (byte-array [0 1 -1 42])
        stored (db/transact! ds #(blobs/store! % settings did content "application/octet-stream"))
        blob {"$type" "blob" "ref" {"$link" (:cid stored)} "mimeType" (:mime_type stored) "size" (:size stored)}]
    (db/transact! ds
      (fn [c]
        (repo/apply-writes! c settings did
                           [{:action :create :collection "com.example.record" :rkey "one" :value {"$type" "com.example.record" "file" blob}}
                            {:action :create :collection "com.example.record" :rkey "hidden" :value {"$type" "com.example.record" "text" "hidden"}}] nil)
        (let [hidden (repo/record c did "com.example.record" "hidden")]
          (moderation/update-status! c {"subject" {"$type" "com.atproto.repo.strongRef" "uri" (:uri hidden) "cid" (:cid hidden)}
                                       "takedown" {"applied" true "ref" "backup-case"}}))
        (preferences/put! c (assoc account :access-scope "com.atproto.access")
                          [{"$type" "app.bsky.actor.defs#adultContentPref" "enabled" false}])))
    {:account account :blob stored :content content}))

(defn round-trip! [extra]
  (with-databases
    (fn [source target source-env target-env]
      (let [directory (.toFile (Files/createTempDirectory "pds-backup-drill-" (make-array java.nio.file.attribute.FileAttribute 0)))
            folder (io/file directory "backup")
            settings (merge (api/settings) extra)
            {:keys [account blob content]} (seed! source settings)
            did (:did account)
            before (snapshot source)
            exported (db/transact! source #(repo/export-car % did))
            key (binding [fixture/*ds* source] (imports/local-key settings did))
            events (with-open [c (db/connection source)] (db/query c "SELECT seq FROM repo_events ORDER BY seq"))]
        (try
          (is (= 0 (:exit (command source-env "backup" folder))))
          (is (= 0 (:exit (command {} "verify" folder))))
          (is (= (PosixFilePermissions/fromString "rw-------") (Files/getPosixFilePermissions (.toPath (io/file folder "database.dump")) (make-array java.nio.file.LinkOption 0))))
          (is (= (PosixFilePermissions/fromString "rwx------") (Files/getPosixFilePermissions (.toPath folder) (make-array java.nio.file.LinkOption 0))))
          (is (= 1 (:exit (command source-env "backup" folder))) "An existing backup cannot be overwritten")
          (is (= 1 (:exit (command source-env "restore" folder))) "An existing PDS cannot be overwritten")
          (is (= before (snapshot source)))
          (is (= 0 (:exit (command target-env "restore" folder))))
          (is (= before (snapshot target)) "Every table, including credentials, private state, jobs and migrations, is restored")
          (is (true? (db/migrate! target)))
          (is (= (vec exported) (vec (db/transact! target #(repo/export-car % did)))))
          (is (= 2 (count (:paths (repository/verify-car exported did key)))))
          (let [server (http/start! settings (app/handler settings target)) port (:port server)]
            (try
              (with-open [client (HttpClient/newHttpClient)]
                (let [login (api/xrpc client port "POST" "com.atproto.server.createSession" {"identifier" did "password" "backup-password"} nil)
                      token (get-in login [:body "accessJwt"])
                      connection (ws/connect client port (str stream/path "?cursor=0"))]
                  (try
                    (is (= 200 (:status login)))
                    (is (= 200 (:status (api/xrpc client port "GET" "com.atproto.server.getSession" nil (:accessJwt account)))))
                    (is (= [{"$type" "app.bsky.actor.defs#adultContentPref" "enabled" false}]
                           (get-in (api/xrpc client port "GET" "app.bsky.actor.getPreferences" nil token) [:body "preferences"])))
                    (is (= 400 (:status (api/xrpc client port "GET" (str "com.atproto.repo.getRecord?repo=" did "&collection=com.example.record&rkey=hidden") nil nil))))
                    (is (= (vec content) (vec (:raw (api/xrpc client port "GET" (str "com.atproto.sync.getBlob?did=" did "&cid=" (:cid blob)) nil nil)))))
                    (let [raw (mapv (fn [_] (ws/receive connection)) events)
                          frames (mapv #(codec/decode-pair % 5000000) raw)]
                      (is (= (mapv :seq events) (mapv #(get (second %) "seq") frames)))
                      (sync-test/verify-upstream! "verify-stream.mjs" {:did did :didKey (str "did:key:" (crypto/multikey "ES256" (:public key)))
                                                                    :expectedTypes ["#identity" "#account" "#commit" "#commit"]
                                                                    :frames (mapv crypto/b64 raw)}))
                    (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.repo.createRecord"
                                                {"repo" did "collection" "com.example.record" "rkey" "after"
                                                 "record" {"$type" "com.example.record" "text" "after restore"}} token))))
                    (let [[header event] (stream/read! connection)]
                      (is (= "#commit" (get header "t")))
                      (is (> (get event "seq") (:seq (last events)))))
                    (is (= 3 (count (:paths (repository/verify-car (db/transact! target #(repo/export-car % did)) did key)))))
                    (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.server.refreshSession" nil (:refreshJwt account)))))
                    (is (= 401 (:status (api/xrpc client port "POST" "com.atproto.server.refreshSession" nil (:refreshJwt account)))))
                    (finally (stream/stop! connection)))))
              (finally ((:stop! server)))))
          (finally
            ;; Only files in this invocation's freshly allocated directory.
            (doseq [file (reverse (file-seq directory))] (io/delete-file file))))))))

(when (= "true" (System/getenv "PDS_TEST_BACKUP"))
  (deftest postgres-backup-restores-a-working-pds (round-trip! {}))
  (deftest invalid-archives-and-nonempty-destinations-are-refused
    (with-databases
      (fn [source target source-env target-env]
        (let [directory (.toFile (Files/createTempDirectory "pds-backup-invalid-" (make-array java.nio.file.attribute.FileAttribute 0)))
              folder (io/file directory "backup")]
          (try
            (seed! source (api/settings))
            (with-open [c (db/connection source)]
              ;; Build the index successfully, then replace its expression
              ;; function with one that fails when pg_restore rebuilds it. The
              ;; resulting archive is intact and needs no binary/SQL matching.
              (db/execute! c "CREATE TABLE public.backup_restore_probe (value integer)")
              (db/execute! c "INSERT INTO public.backup_restore_probe VALUES (42)")
              (db/execute! c "CREATE FUNCTION public.backup_restore_probe_key(integer) RETURNS integer
                              LANGUAGE plpgsql IMMUTABLE AS $$ BEGIN RETURN $1; END $$")
              (db/execute! c "CREATE INDEX backup_restore_probe_idx ON public.backup_restore_probe (public.backup_restore_probe_key(value))")
              (is (= [{:value 42}] (db/query c "SELECT value FROM public.backup_restore_probe")))
              (db/execute! c "CREATE OR REPLACE FUNCTION public.backup_restore_probe_key(integer) RETURNS integer
                              LANGUAGE plpgsql IMMUTABLE AS $$ BEGIN RAISE EXCEPTION 'backup restore probe index failure'; END $$"))
            (is (= 0 (:exit (command source-env "backup" folder))))
            (with-open [c (db/connection target)] (db/execute! c "CREATE TABLE sentinel (value text); INSERT INTO sentinel VALUES ('keep')"))
            (is (= 1 (:exit (command target-env "restore" folder))))
            (with-open [c (db/connection target)]
              (is (= [{:value "keep"}] (db/query c "SELECT * FROM sentinel")))
              (db/execute! c "DROP TABLE sentinel"))
            (let [dump (io/file folder "database.dump")
                  original (Files/readAllBytes (.toPath dump))]
              (with-open [file (java.io.RandomAccessFile. dump "rw")] (.setLength file (- (.length file) 1024)))
              (is (= 1 (:exit (command {} "verify" folder))))
              (is (= 1 (:exit (command target-env "restore" folder))))
              (is (empty? (keys (snapshot target))))
              ;; The original checksum must verify again. PostgreSQL itself
              ;; raises the late failure when rebuilding the expression index.
              (Files/write (.toPath dump) original (make-array java.nio.file.OpenOption 0))
              (is (= 0 (:exit (command {} "verify" folder))))
              (let [result (command target-env "restore" folder)]
                (is (= 1 (:exit result)))
                (is (str/includes? (:output result) "pg_restore failed") "Failure reaches PostgreSQL, not preflight verification"))
              (is (empty? (keys (snapshot target))) "DDL and data are rolled back together")
              ;; Negative control in this disposable target: without a single
              ;; transaction, the same late failure leaves table data behind.
              ;; This proves the fixture reaches the post-data phase instead of
              ;; merely testing an unreadable archive or an early SQL error.
              (let [restore-tool (if-let [bin (System/getenv "PG_BIN")] (str (io/file bin "pg_restore")) "pg_restore")
                    result (run-command target-env [restore-tool "--exit-on-error" "--no-owner" "--no-acl"
                                                    "--dbname" (get target-env "PGDATABASE") (str dump)])]
                (is (not (zero? (:exit result))))
                (is (str/includes? (:output result) "backup restore probe index failure"))
                (with-open [c (db/connection target)]
                  (is (= [{:value 42}] (db/query c "SELECT value FROM public.backup_restore_probe")))
                  (is (= 1 (:n (first (db/query c "SELECT count(*) AS n FROM public.accounts"))))))))
            (finally (doseq [file (reverse (file-seq directory))] (io/delete-file file))))))))
  (when-let [endpoint (System/getenv "PDS_TEST_S3_ENDPOINT")]
    (deftest database-backup-preserves-external-blob-locators
      (let [bucket (str "pds-backup-" (UUID/randomUUID))
            config (s3/settings (assoc s3-test/config "PDS_S3_ENDPOINT" endpoint "PDS_S3_BUCKET" bucket))]
        (with-open [store (s3/open-store config)]
          (.createBucket ^S3Client (:client store) ^CreateBucketRequest (-> (CreateBucketRequest/builder) (.bucket bucket) .build))
          (round-trip! {:blob-store store}))))))
