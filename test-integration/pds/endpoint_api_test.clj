(ns pds.endpoint-api-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.walk :as walk]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.invites-test :as invites]
            [pds.repo-import-test :as imports]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)

(defn snapshot []
  (with-open [conn (db/connection fixture/*ds*)]
    (walk/postwalk #(if (bytes? %) (vec %) %)
                   (mapv #(db/query conn (str "SELECT * FROM " %))
                         ["accounts" "sessions" "repositories" "records" "repo_blocks" "repo_events" "email_outbox"]))))

(deftest schemas-reject-invalid-input-without-side-effects
  (let [settings (assoc (api/settings) :admin-password invites/admin-password)
        account (imports/local! settings) did (:did account) token (:accessJwt account)
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)
        value {"$type" "com.example.file" "value" "hello"}
        body {"repo" did "collection" "com.example.file" "rkey" "one" "record" value}]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        ;; Authentication still precedes reading the JSON request on protected
        ;; writes. Schema validation does not turn these into unauthenticated 400s.
        (is (= 401 (:status (api/xrpc client port "POST" "com.atproto.repo.putRecord" {} nil))))
        (let [before (snapshot)]
          (doseq [[method input] [["com.atproto.repo.putRecord" (dissoc body "rkey")]
                                  ["com.atproto.repo.putRecord" (assoc body "swapCommit" nil)]
                                  ["com.atproto.repo.createRecord" (assoc body "swapCommit" "bad")]
                                  ["com.atproto.repo.createRecord" (assoc body "record" [])]
                                  ["com.atproto.repo.applyWrites"
                                   {"repo" did "writes" [{"$type" "com.atproto.repo.applyWrites#create"
                                                          "collection" "com.example.file" "rkey" "first" "value" value}
                                                         {"$type" "com.atproto.repo.applyWrites#update"
                                                          "collection" "com.example.file" "value" value}]}]
                                  ["com.atproto.server.createAccount"
                                   {"handle" "new.example.com" "email" "new@example.com" "password" "long-password" "verificationPhone" 42}]
                                  ["com.atproto.server.createSession"
                                   {"identifier" did "password" "long-password" "allowTakendown" "false"}]]]
            (let [response (api/xrpc client port "POST" method input token)]
              (is (= 400 (:status response)) method)
              (is (= "InvalidRequest" (get-in response [:body "error"])) method)))
          (is (= before (snapshot))))
        (doseq [path [(str "com.atproto.sync.getRepo?did=" did "&since=not-a-tid")
                      (str "com.atproto.repo.getRecord?repo=" did "&collection=com.example.file&rkey=one&cid=bad")
                      "com.atproto.sync.getLatestCommit?did=alice.example.com"
                      "com.atproto.sync.getRepoStatus?did=not-a-did"]]
          (let [response (api/xrpc client port "GET" path nil nil)]
            (is (= 400 (:status response)))
            (is (= "InvalidRequest" (get-in response [:body "error"])))))
        ;; Null is meaningful for putRecord's swapRecord, and future fields are
        ;; allowed. Record validation mode remains independent of the envelope.
        (let [created (api/xrpc client port "POST" "com.atproto.repo.putRecord"
                                (assoc body "swapRecord" nil "futureField" {"enabled" true} "validate" false) token)]
          (is (= 200 (:status created)))
          (is (not (contains? (:body created) "validationStatus"))))
        (let [conflict (api/xrpc client port "POST" "com.atproto.repo.putRecord" (assoc body "swapRecord" nil) token)]
          (is (= 400 (:status conflict)))
          (is (= "InvalidSwap" (get-in conflict [:body "error"]))))
        (let [invited (invites/admin-call client port "com.atproto.server.createInviteCodes" {"useCount" 2.0})]
          (is (= 200 (:status invited)))
          (is (= 1 (count (get-in invited [:body "codes" 0 "codes"]))))))
      (finally ((:stop! server))))))
