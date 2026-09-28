(ns pds.sqlite-backend-test
  "End-to-end slice on the SQLite backend. Runs against its own temporary
  database file regardless of PDS_TEST_DATABASE_URL, so the default suite
  always exercises both dialects."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.db :as db]
            [pds.http :as http]
            [pds.invites :as invites]
            [pds.reserved-keys :as reserved-keys]
            [pds.server-api-test :as api])
  (:import [java.io File]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def ^:dynamic *ds* nil)

(defn sqlite-database [f]
  (let [file (File/createTempFile "pds-sqlite-" ".sqlite3")]
    (try
      (with-open [pool (db/open-pool! {:url (str "jdbc:sqlite:" (.getPath file))}
                                      {:maximum-size 4 :timeout-ms 5000})]
        (db/migrate! pool)
        (binding [*ds* pool] (f)))
      (finally
        (doseq [suffix ["" "-wal" "-shm"]]
          (.delete (File. (str (.getPath file) suffix))))))))

(use-fixtures :each sqlite-database)

(defn- rows [sql & params] (with-open [conn (db/connection *ds*)] (apply db/query conn sql params)))
(defn- email-token [subject]
  (let [row (last (rows "SELECT payload::text AS payload FROM email_outbox WHERE payload->>'subject' = ? ORDER BY created_at" subject))]
    (second (re-find #"Your token is: ([A-Za-z0-9_-]+)" (get (json/read-str (:payload row)) "text")))))

(deftest accounts-records-blobs-and-sessions-round-trip-on-sqlite
  (let [settings (assoc (api/settings) :invite-required true)
        code (db/transact! *ds* #(invites/create! % "admin" 2))
        server (http/start! settings (app/handler settings *ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client (:port server) %1 %2 %3 %4)
              created (call "POST" "com.atproto.server.createAccount"
                            {"handle" "alice.example.com" "email" "alice@example.com"
                             "password" "correct-password" "inviteCode" code} nil)
              did (get-in created [:body "did"]) access (get-in created [:body "accessJwt"])]
          (is (= 200 (:status created)))
          (is (= "did:web:alice.example.com" did))
          (is (= 400 (:status (call "POST" "com.atproto.server.createAccount"
                                    {"handle" "bob.example.com" "email" "alice@example.com"
                                     "password" "correct-password" "inviteCode" code} nil)))
              "Unique constraints reject a duplicate email")

          ;; Sessions: bearer auth, refresh rotation, replay revocation.
          (let [session (:body (call "POST" "com.atproto.server.createSession"
                                     {"identifier" "alice.example.com" "password" "correct-password"} nil))
                refreshed (call "POST" "com.atproto.server.refreshSession" nil (get session "refreshJwt"))]
            (is (= 200 (:status refreshed)))
            (is (= 401 (:status (call "POST" "com.atproto.server.refreshSession" nil (get session "refreshJwt")))))
            (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil (get session "accessJwt")))))
            (is (= did (get-in (call "GET" "com.atproto.server.getSession" nil access) [:body "did"]))))

          ;; Signed repository writes, reads and pagination.
          (dotimes [n 3]
            (is (= 200 (:status (call "POST" "com.atproto.repo.createRecord"
                                      {"repo" did "collection" "com.example.note" "rkey" (str "note-" n)
                                       "record" {"$type" "com.example.note" "n" n}} access)))))
          (is (= 200 (:status (call "POST" "com.atproto.repo.deleteRecord"
                                    {"repo" did "collection" "com.example.note" "rkey" "note-1"} access))))
          (let [listed (:body (call "GET" "com.atproto.repo.listRecords?repo=alice.example.com&collection=com.example.note&limit=1" nil nil))]
            (is (= 1 (count (get listed "records"))))
            (is (string? (get listed "cursor"))))
          (is (= 0 (get-in (call "GET" (str "com.atproto.repo.getRecord?repo=" did "&collection=com.example.note&rkey=note-0") nil nil)
                           [:body "value" "n"])))
          (is (= [did] (mapv #(get % "did") (get-in (call "GET" "com.atproto.sync.listRepos" nil nil) [:body "repos"]))))
          (is (= [did] (mapv #(get % "did")
                             (get-in (call "GET" "com.atproto.sync.listReposByCollection?collection=com.example.note" nil nil)
                                     [:body "repos"]))))

          ;; Full CAR export twice through one pooled connection set: the
          ;; transaction-local dedup table must reset between exports.
          (dotimes [_ 2]
            (let [response (call "GET" (str "com.atproto.sync.getRepo?did=" did) nil nil)]
              (is (= 200 (:status response)))
              (is (pos? (alength ^bytes (:raw response))))))

          ;; Blobs: staged upload, referenced download, byte-ranged reads.
          (let [content (byte-array (concat (repeat 100000 7) [1 2 3]))
                upload (.send client
                              (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" (:port server) "/xrpc/com.atproto.repo.uploadBlob")))
                                  (.header "Authorization" (str "Bearer " access))
                                  (.header "Content-Type" "application/octet-stream")
                                  (.POST (HttpRequest$BodyPublishers/ofByteArray content))
                                  .build)
                              (HttpResponse$BodyHandlers/ofString))
                blob (json/read-str (.body upload))
                _ (is (map? (get blob "blob")))
                ref {"$type" "com.example.media" "file" (get blob "blob")}
                _ (is (= 200 (:status (call "POST" "com.atproto.repo.createRecord"
                                            {"repo" did "collection" "com.example.media" "rkey" "pic"
                                             "record" ref "validate" false} access))))
                cid (get-in blob ["blob" "ref" "$link"])
                downloaded (call "GET" (str "com.atproto.sync.getBlob?did=" did "&cid=" cid) nil nil)]
            (is (= 200 (:status downloaded)))
            (is (java.util.Arrays/equals content ^bytes (:raw downloaded)))
            (is (= [] (get-in (call "GET" "com.atproto.repo.listMissingBlobs" nil access) [:body "blobs"]))))

          ;; Durable events with autoincrement sequencing survive lookups.
          (is (pos? (:n (first (rows "SELECT max(seq) AS n FROM repo_events")))))
          (is (= "active" (:status (first (rows "SELECT status FROM accounts WHERE did = ?" did)))))

          ;; App passwords and epoch-triggered OAuth invalidation.
          (let [app-password (get-in (call "POST" "com.atproto.server.createAppPassword" {"name" "phone"} access) [:body "password"])
                epoch (:oauth_epoch (first (rows "SELECT oauth_epoch FROM accounts WHERE did = ?" did)))]
            (is (= 200 (:status (call "POST" "com.atproto.server.createSession"
                                      {"identifier" did "password" app-password} nil))))
            (is (= 200 (:status (call "POST" "com.atproto.server.requestPasswordReset" {"email" "alice@example.com"} nil))))
            (is (= 200 (:status (call "POST" "com.atproto.server.resetPassword"
                                      {"token" (email-token "Reset your PDS password") "password" "replacement-password"} nil))))
            (is (> (:oauth_epoch (first (rows "SELECT oauth_epoch FROM accounts WHERE did = ?" did))) epoch)
                "The SQLite trigger advances the OAuth epoch on credential changes")
            (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil access)))))

          ;; Lifecycle: deactivation hides content, activation restores it.
          (let [access (get-in (call "POST" "com.atproto.server.createSession"
                                     {"identifier" did "password" "replacement-password"} nil) [:body "accessJwt"])]
            (is (= 200 (:status (call "POST" "com.atproto.server.deactivateAccount" {} access))))
            (is (= 400 (:status (call "GET" (str "com.atproto.sync.getRepo?did=" did) nil nil))))
            (is (= 200 (:status (call "POST" "com.atproto.server.activateAccount" nil access))))
            (is (= 200 (:status (call "GET" (str "com.atproto.sync.getRepo?did=" did) nil nil)))))))
      (finally ((:stop! server))))))

(deftest interval-arithmetic-and-reservations-behave-on-sqlite
  (let [settings (api/settings)
        reserved (reserved-keys/reserve! *ds* settings {"did" "did:web:future.example.com"})]
    (is (= reserved (reserved-keys/reserve! *ds* settings {"did" "did:web:future.example.com"})))
    (db/transact! *ds* #(db/execute! % "UPDATE reserved_signing_keys SET created_at = now() - interval '25 hours'"))
    (is (not= reserved (reserved-keys/reserve! *ds* settings {"did" "did:web:future.example.com"}))
        "Expiry sweeps translate interval arithmetic")
    (let [alice (accounts/create! *ds* settings {"handle" "alice.example.com" "email" "alice@example.com"
                                                 "password" "correct-password"})]
      (db/transact! *ds* (fn [conn]
                           (accounts/issue-email! conn (first (db/query conn "SELECT * FROM accounts WHERE did = ?" (:did alice)))
                                                  "reset-password")))
      (is (string? (email-token "Reset your PDS password")))
      (is (= 1 (count (rows "SELECT 1 FROM account_tokens WHERE purpose = 'reset-password' AND expires_at > now()"))))
      (db/transact! *ds* #(db/execute! % "UPDATE account_tokens SET expires_at = now() - interval '60 seconds'"))
      (is (= [] (rows "SELECT 1 FROM account_tokens WHERE expires_at > now()"))))))
