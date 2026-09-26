(ns pds.account-lifecycle-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.auth :as auth]
            [pds.blob-cleanup :as blob-cleanup]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.repo :as repo]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)

(deftest deactivate-and-reactivate-over-http
  (let [settings (api/settings)
        account (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "test-password"})
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)
        token (:accessJwt account) did (:did account)
        record {"repo" did "collection" "com.example.record" "rkey" "one" "record" {"$type" "com.example.record" "v" 1}}]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client port %1 %2 %3 %4)
              app-password (get-in (call "POST" "com.atproto.server.createAppPassword" {"name" "app"} token) [:body "password"])
              app-login (call "POST" "com.atproto.server.createSession" {"identifier" did "password" app-password} nil)
              app-token (get-in app-login [:body "accessJwt"])]
          (is (= 200 (:status (call "POST" "com.atproto.repo.createRecord" record token))))
          (is (= 403 (:status (call "POST" "com.atproto.server.deactivateAccount" {} app-token))))
          (is (= 400 (:status (call "POST" "com.atproto.server.deactivateAccount" {"deleteAfter" "yesterday"} token))))
          (is (= 200 (:status (call "POST" "com.atproto.server.deactivateAccount" {"deleteAfter" "2027-01-01T00:00:00Z"} token))))
          (let [status (call "GET" (str "com.atproto.sync.getRepoStatus?did=" did) nil nil)]
            (is (false? (get-in status [:body "active"])))
            (is (= "deactivated" (get-in status [:body "status"]))))
          (doseq [path [(str "com.atproto.repo.getRecord?repo=" did "&collection=com.example.record&rkey=one")
                        (str "com.atproto.sync.getRepo?did=" did)
                        (str "com.atproto.sync.listBlobs?did=" did)]]
            (is (= 400 (:status (call "GET" path nil nil)))))
          (is (= [] (get-in (call "GET" "com.atproto.sync.listRepos" nil nil) [:body "repos"])))
          (is (= 401 (:status (call "POST" "com.atproto.repo.putRecord" record token))))
          (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil app-token))))
          (is (= 401 (:status (call "POST" "com.atproto.server.activateAccount" nil app-token))))
          (is (= 401 (:status (call "POST" "com.atproto.server.refreshSession" nil (get-in app-login [:body "refreshJwt"])))))
          (is (= 401 (:status (call "POST" "com.atproto.server.createSession" {"identifier" did "password" app-password} nil))))
          (is (= did (get-in (call "GET" "com.atproto.identity.resolveHandle?handle=alice.example.com" nil nil) [:body "did"])))
          (is (false? (get-in (call "GET" "com.atproto.server.getSession" nil token) [:body "active"])))
          (let [login (call "POST" "com.atproto.server.createSession" {"identifier" did "password" "test-password"} nil)
                refreshed (call "POST" "com.atproto.server.refreshSession" nil (get-in login [:body "refreshJwt"]))]
            (is (= 200 (:status login) (:status refreshed)))
            (is (= "deactivated" (get-in refreshed [:body "status"])))
            (is (= 200 (:status (call "POST" "com.atproto.server.activateAccount" nil (get-in refreshed [:body "accessJwt"]))))))
          (is (= 200 (:status (call "POST" "com.atproto.repo.putRecord" record token))))
          (is (true? (get-in (call "GET" "com.atproto.server.getSession" nil token) [:body "active"])))
          (with-open [conn (db/connection fixture/*ds*)]
            (is (= ["account" "account" "account"] (mapv :event_type (db/query conn "SELECT event_type FROM repo_events WHERE event_type = 'account' ORDER BY seq"))))
            (is (nil? (:delete_after (first (db/query conn "SELECT delete_after FROM accounts WHERE did = ?" did))))))))
      (finally ((:stop! server))))))

(deftest deactivation-serializes-with-writes
  (let [settings (api/settings)
        account (accounts/create! fixture/*ds* settings {"handle" "race.example.com" "email" "race@example.com" "password" "test-password"})
        request {:headers {"authorization" (str "Bearer " (:accessJwt account))}}
        did (:did account)]
    (dotimes [i 3]
      (let [start (promise)
            write (future
                    @start
                    (try
                      (db/transact! fixture/*ds*
                        (fn [conn]
                          (auth/authenticate! conn settings request)
                          (repo/apply-writes! conn settings did [{:action :create :collection "com.example.record"
                                                                 :rkey (str i) :value {"$type" "com.example.record"}}] nil)))
                      :written
                      (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))
            deactivate (future
                         @start
                         (db/transact! fixture/*ds*
                           (fn [conn]
                             (accounts/deactivate! conn (auth/authenticate! conn settings request) {}))))]
        (deliver start true)
        @deactivate
        (is (#{:written 401} @write))
        (with-open [conn (db/connection fixture/*ds*)]
          (is (= "account" (:event_type (first (db/query conn "SELECT event_type FROM repo_events WHERE did = ? ORDER BY seq DESC LIMIT 1" did))))
              "A record commit cannot appear after the deactivation event"))
        (db/transact! fixture/*ds*
          #(accounts/activate! % (auth/authenticate! % settings request {:allow-deactivated? true})))))))

(deftest email-backed-deletion-and-durable-object-cleanup
  (let [settings (api/settings)
        alice (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "test-password"})
        bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})
        did (:did alice) access (:accessJwt alice) data (byte-array [1 2 3])
        store (reify blobs/ObjectStore
                (put-object! [_ _ _ _ _] {:object-key "alice/blob" :object-bucket "test-bucket"})
                (get-object! [_ _ _ _] data))
        stored (db/transact! fixture/*ds* #(blobs/store! % {:blob-store store} did data "image/png"))
        _ (db/transact! fixture/*ds* #(blobs/store! % {} (:did bob) data "image/png"))
        _ (db/transact! fixture/*ds*
            #(repo/apply-writes! % settings (:did bob)
               [{:action :create :collection "com.example.file" :rkey "one"
                 :value {"$type" "com.example.file" "file" {"$type" "blob" "ref" {"$link" (:cid stored)} "mimeType" "image/png" "size" 3}}}] nil))
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client port %1 %2 %3 %4)
              app-password (get-in (call "POST" "com.atproto.server.createAppPassword" {"name" "app"} access) [:body "password"])
              app-login (call "POST" "com.atproto.server.createSession" {"identifier" did "password" app-password} nil)]
          (is (= 403 (:status (call "POST" "com.atproto.server.requestAccountDelete" nil (get-in app-login [:body "accessJwt"])))))
          (is (= 200 (:status (call "POST" "com.atproto.server.deactivateAccount" {} access))))
          (is (= 200 (:status (call "POST" "com.atproto.server.requestAccountDelete" nil access))))
          (let [token (api/email-token "Confirm account deletion")
                body {"did" did "password" "test-password" "token" token}]
            (is (= 401 (:status (call "POST" "com.atproto.server.deleteAccount" (assoc body "password" app-password) nil))))
            (is (= 400 (:status (call "POST" "com.atproto.server.deleteAccount" (assoc body "did" (:did bob)) nil))))
            (is (= 400 (:status (call "POST" "com.atproto.server.deleteAccount" (assoc body "token" "invalid") nil))))
            (is (= 200 (:status (call "POST" "com.atproto.server.deleteAccount" body nil))))
            (is (= 401 (:status (call "POST" "com.atproto.server.deleteAccount" body nil)))))
          (is (= "deleted" (get-in (call "GET" (str "com.atproto.sync.getRepoStatus?did=" did) nil nil) [:body "status"])))
          (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil access))))
          (is (= 401 (:status (call "POST" "com.atproto.server.refreshSession" nil (:refreshJwt alice)))))
          (is (= 401 (:status (call "POST" "com.atproto.server.createSession" {"identifier" did "password" "test-password"} nil))))
          (is (= 400 (:status (call "GET" (str "com.atproto.sync.getBlob?did=" did "&cid=" (:cid stored)) nil nil))))
          (is (= 200 (:status (call "GET" (str "com.atproto.sync.getBlob?did=" (:did bob) "&cid=" (:cid stored)) nil nil))))
          (with-open [conn (db/connection fixture/*ds*)]
            (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ?" did))]
              (is (= "deleted" (:status account)))
              (is (nil? (:email account))) (is (nil? (:password_hash account))))
            (doseq [table ["repositories" "records" "blobs" "sessions" "app_passwords" "account_tokens"]]
              (is (empty? (db/query conn (str "SELECT * FROM " table " WHERE did = ?") did))))
            (is (= 1 (:count (first (db/query conn "SELECT count(*) AS count FROM blob_delete_jobs"))))))
          (let [broken (reify blobs/ObjectDeletion (delete-object! [_ _ _] (throw (ex-info "private provider details" {}))))]
            (is (= :retry (blob-cleanup/delete-one! fixture/*ds* broken))))
          (db/transact! fixture/*ds* #(db/execute! % "UPDATE blob_delete_jobs SET available_at = now()"))
          (let [deleted (atom []) healthy (reify blobs/ObjectDeletion (delete-object! [_ bucket key] (swap! deleted conj [bucket key])))
                workers [(future (blob-cleanup/delete-one! fixture/*ds* healthy)) (future (blob-cleanup/delete-one! fixture/*ds* healthy))]]
            (is (= #{:deleted nil} (set (mapv deref workers))))
            (is (= [["test-bucket" "alice/blob"]] @deleted)))
          (with-open [conn (db/connection fixture/*ds*)] (is (empty? (db/query conn "SELECT * FROM blob_delete_jobs"))))))
      (finally ((:stop! server))))))
