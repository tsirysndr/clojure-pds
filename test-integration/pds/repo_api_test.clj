(ns pds.repo-api-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.protocol.car :as car]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))
(use-fixtures :each fixture/isolated-database)
(deftest records-over-http
  (let [settings (api/settings)
        alice (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "test-password"})
        bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)
        did (:did alice) token (:accessJwt alice) collection "app.bsky.feed.post"
        body {"repo" did "collection" collection "rkey" "one" "record" {"$type" collection "text" "Hello"}}]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [created (api/xrpc client port "POST" "com.atproto.repo.createRecord" body token)
              get-path (str "com.atproto.repo.getRecord?repo=" did "&collection=" collection "&rkey=one")
              fetched (api/xrpc client port "GET" get-path nil nil)]
          (is (= 200 (:status created) (:status fetched)))
          (is (= (get-in created [:body "cid"]) (get-in fetched [:body "cid"])))
          (is (= "Hello" (get-in fetched [:body "value" "text"])))
          (is (= 403 (:status (api/xrpc client port "POST" "com.atproto.repo.putRecord" body (:accessJwt bob)))))
          (is (= 400 (:status (api/xrpc client port "POST" "com.atproto.repo.putRecord" (assoc body "swapRecord" nil) token))))
          (is (= 400 (:status (api/xrpc client port "POST" "com.atproto.repo.createRecord" body token))))
          (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.repo.createRecord" (assoc body "rkey" "two") token))))
          (let [first-page (api/xrpc client port "GET" (str "com.atproto.repo.listRecords?repo=" did "&collection=" collection "&limit=1") nil nil)
                next-page (api/xrpc client port "GET" (str "com.atproto.repo.listRecords?repo=" did "&collection=" collection "&limit=1&cursor=" (get-in first-page [:body "cursor"])) nil nil)]
            (is (= 1 (count (get-in first-page [:body "records"]))))
            (is (= (str "at://" did "/" collection "/two") (get-in next-page [:body "records" 0 "uri"])))
            (is (nil? (get-in next-page [:body "cursor"]))))
          (let [export (api/xrpc client port "GET" (str "com.atproto.sync.getRepo?did=" did) nil nil)
                latest (api/xrpc client port "GET" (str "com.atproto.sync.getLatestCommit?did=" did) nil nil)]
            (is (= 200 (:status export)))
            (is (= (get-in latest [:body "cid"]) (first (:roots (car/decode (:raw export)))))))
          (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.repo.deleteRecord" (dissoc body "record") token))))
          (is (= 400 (:status (api/xrpc client port "GET" get-path nil nil))))))
      (finally ((:stop! server))))))
