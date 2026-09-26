(ns pds.response-contract-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.db-test :as fixture]
            [pds.firehose-test :as firehose]
            [pds.http :as http]
            [pds.protocol.car :as car]
            [pds.server-api-test :as api]
            [pds.websocket-test :as ws])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)

(deftest repository-description-reactivation-checkpoint-and-session-revocation
  (let [settings (api/settings) server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [created (api/xrpc client port "POST" "com.atproto.server.createAccount"
                                {"handle" "contract.example.com" "email" "contract@example.com" "password" "test-password"} nil)
              account (:body created) did (get account "did") access (get account "accessJwt") refresh (get account "refreshJwt")
              call #(api/xrpc client port %1 %2 %3 access)]
          (is (= 200 (:status created)))
          (is (= 200 (:status (call "POST" "com.atproto.repo.createRecord"
                                   {"repo" did "collection" "com.example.record" "rkey" "one"
                                    "record" {"$type" "com.example.record" "text" "contract"}}))))
          (let [description (call "GET" (str "com.atproto.repo.describeRepo?repo=" did) nil)]
            (is (= 200 (:status description)))
            (is (= did (get-in description [:body "didDoc" "id"])))
            (is (= ["com.example.record"] (get-in description [:body "collections"]))))
          (is (= 200 (:status (call "POST" "com.atproto.server.requestEmailConfirmation" nil))))
          (let [connection (ws/connect client port (str firehose/path "?cursor=0"))]
            (try
              (is (= ["#identity" "#account" "#commit" "#commit"]
                     (mapv #(get (first %) "t") (repeatedly 4 #(firehose/read! connection)))))
              (is (= 200 (:status (call "POST" "com.atproto.server.deactivateAccount" {}))))
              (is (= "deactivated" (get (second (firehose/read! connection)) "status")))
              (is (= 200 (:status (call "POST" "com.atproto.server.activateAccount" nil))))
              (let [[header account-event] (firehose/read! connection)
                    [sync-header sync-event] (firehose/read! connection)
                    latest (:body (call "GET" (str "com.atproto.sync.getLatestCommit?did=" did) nil))]
                (is (= "#account" (get header "t")))
                (is (true? (get account-event "active")))
                (is (= "#sync" (get sync-header "t")))
                (is (< (get account-event "seq") (get sync-event "seq")))
                (is (= (get latest "rev") (get sync-event "rev")))
                (is (= [(get latest "cid")] (:roots (car/decode (get sync-event "blocks"))))))
              (finally (firehose/stop! connection))))
          (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.server.deleteSession" nil refresh))))
          (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil))))
          (is (= 401 (:status (api/xrpc client port "POST" "com.atproto.server.refreshSession" nil refresh))))
          (is (= 200 (:status (call "GET" (str "com.atproto.repo.describeRepo?repo=" did) nil))))))
      (finally ((:stop! server))))))
