(ns pds.account-lifecycle-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.auth :as auth]
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
            (is (= ["account" "account"] (mapv :event_type (db/query conn "SELECT event_type FROM repo_events WHERE event_type = 'account' ORDER BY seq"))))
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
