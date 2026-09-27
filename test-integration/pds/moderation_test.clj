(ns pds.moderation-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.invites :as invites]
            [pds.invites-test :as invites-test]
            [pds.protocol.codec :as codec]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)

(deftest account-takedown-and-independent-deactivation
  (let [settings (assoc (api/settings) :admin-password invites-test/admin-password)
        alice (accounts/create! fixture/*ds* settings (invites-test/signup "alice" nil))
        did (:did alice) token (:accessJwt alice)
        code (db/transact! fixture/*ds* #(invites/create! % did 1))
        subject {"$type" "com.atproto.admin.defs#repoRef" "did" did}
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client port %1 %2 %3 %4)
              admin #(invites-test/admin-call client port %1 %2 %3)
              update-status #(admin "POST" "com.atproto.admin.updateSubjectStatus" (assoc % "subject" subject))
              get-status #(admin "GET" (str "com.atproto.admin.getSubjectStatus?did=" did) nil)]
          (is (= 401 (:status (call "GET" (str "com.atproto.admin.getAccountInfo?did=" did) nil token))))
          (let [info (admin "GET" (str "com.atproto.admin.getAccountInfo?did=" did) nil)]
            (is (= 200 (:status info)))
            (is (= "alice@example.com" (get-in info [:body "email"])))
            (is (= code (get-in info [:body "invites" 0 "code"]))))
          (is (= 401 (:status (call "GET" (str "com.atproto.admin.getAccountInfos?dids=" did) nil token))))
          (is (= 400 (:status (admin "GET" "com.atproto.admin.getAccountInfos?dids=not-a-did" nil))))
          (let [infos (admin "GET" (str "com.atproto.admin.getAccountInfos?dids=" did "&dids=" did
                                        "&dids=did:plc:aaaaaaaaaaaaaaaaaaaaaaaa") nil)]
            (is (= 200 (:status infos)))
            (is (= [did] (mapv #(get % "did") (get-in infos [:body "infos"]))))
            (is (= "alice@example.com" (get-in infos [:body "infos" 0 "email"]))))
          (is (= 200 (:status (call "POST" "com.atproto.repo.createRecord"
                                   {"repo" did "collection" "com.example.record" "rkey" "one"
                                    "record" {"$type" "com.example.record"}} token))))
          (is (= 400 (:status (update-status {"takedown" {"applied" "true"}}))))
          (is (false? (get-in (get-status) [:body "takedown" "applied"])))
          (is (= 200 (:status (update-status {"takedown" {"applied" true "ref" "case-123"}}))))
          (is (= {"applied" true "ref" "case-123"} (get-in (get-status) [:body "takedown"])))
          (is (= "takendown" (get-in (call "GET" (str "com.atproto.sync.getRepoStatus?did=" did) nil nil) [:body "status"])))
          (doseq [path [(str "com.atproto.sync.getRepo?did=" did)
                        (str "com.atproto.repo.getRecord?repo=" did "&collection=com.example.record&rkey=one")
                        (str "com.atproto.sync.listBlobs?did=" did)]]
            (is (= 400 (:status (call "GET" path nil nil)))))
          (is (= [] (get-in (call "GET" "com.atproto.sync.listRepos" nil nil) [:body "repos"])))
          (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil token))))
          (is (= 401 (:status (call "POST" "com.atproto.server.activateAccount" nil token))))
          (is (= 401 (:status (call "POST" "com.atproto.server.refreshSession" nil (:refreshJwt alice)))))
          (is (= 401 (:status (call "POST" "com.atproto.server.createSession" {"identifier" did "password" "signup-password"} nil))))
          (is (= 400 (:status (call "POST" "com.atproto.server.createAccount" (invites-test/signup "blocked" code) nil))))
          (is (= did (get-in (call "GET" "com.atproto.identity.resolveHandle?handle=alice.example.com" nil nil) [:body "did"])))
          ;; Change the underlying activation state while the takedown remains.
          (is (= 200 (:status (update-status {"deactivated" {"applied" true}}))))
          (is (true? (get-in (get-status) [:body "deactivated" "applied"])))
          (is (= 200 (:status (update-status {"takedown" {"applied" false}}))))
          (is (= {"applied" false} (get-in (get-status) [:body "takedown"])))
          (is (= "deactivated" (get-in (call "GET" "com.atproto.server.getSession" nil token) [:body "status"])))
          (is (= 200 (:status (call "POST" "com.atproto.server.activateAccount" nil token))))
          (is (true? (get-in (call "GET" (str "com.atproto.sync.getRepoStatus?did=" did) nil nil) [:body "active"])))
          ;; A user's earlier deactivation must also survive takedown/removal.
          (is (= 200 (:status (call "POST" "com.atproto.server.deactivateAccount" {} token))))
          (is (= 200 (:status (update-status {"takedown" {"applied" true}}))))
          (is (= 200 (:status (update-status {"takedown" {"applied" false}}))))
          (is (true? (get-in (get-status) [:body "deactivated" "applied"])))
          (is (= 200 (:status (update-status {"deactivated" {"applied" false}}))))
          (is (= 200 (:status (call "GET" (str "com.atproto.sync.getRepo?did=" did) nil nil))))
          (is (= 200 (:status (call "POST" "com.atproto.server.createAccount" (invites-test/signup "invited" code) nil))))
          (with-open [conn (db/connection fixture/*ds*)]
            (let [events (mapv #(codec/decode (:payload %)) (db/query conn "SELECT payload FROM repo_events WHERE did = ? AND event_type = 'account' ORDER BY seq" did))]
              (is (= [nil "takendown" "deactivated" nil "deactivated" "takendown" "deactivated" nil]
                     (mapv #(get % "status") events)))))))
      (finally ((:stop! server))))))
