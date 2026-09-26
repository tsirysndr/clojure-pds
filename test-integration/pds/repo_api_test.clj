(ns pds.repo-api-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.db :as db]
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
        did (:did alice) token (:accessJwt alice) collection "com.example.post"
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

(deftest schema-validation-over-http
  (let [settings (api/settings)
        account (accounts/create! fixture/*ds* settings {"handle" "schema.example.com" "email" "schema@example.com" "password" "test-password"})
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)
        did (:did account) token (:accessJwt account)
        post {"$type" "app.bsky.feed.post" "text" "Hello 👋" "createdAt" "2026-09-26T00:00:00Z"}
        body {"repo" did "collection" "app.bsky.feed.post" "record" post}
        snapshot (fn [] (with-open [conn (db/connection fixture/*ds*)]
                          [(db/query conn "SELECT head, rev FROM repositories WHERE did = ?" did)
                           (db/query conn "SELECT count(*) AS count FROM repo_blocks")
                           (db/query conn "SELECT count(*) AS count FROM repo_events")
                           (db/query conn "SELECT count(*) AS count FROM records")]))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [write #(api/xrpc client port "POST" "com.atproto.repo.createRecord" % token)
              before (snapshot)]
          (doseq [invalid [(update body "record" dissoc "createdAt")
                           (assoc body "rkey" "not-a-tid")
                           (assoc-in body ["record" "text"] (apply str (repeat 301 "x")))
                           (assoc-in body ["record" "embed"] {"$type" "app.bsky.embed.images"})
                           (assoc body "validate" "true") (assoc body "validate" nil)]]
            (is (= 400 (:status (write invalid)))))
          (is (= before (snapshot)))
          (doseq [mode [nil true]]
            (let [result (write (cond-> body (some? mode) (assoc "validate" mode)))]
              (is (= 200 (:status result)))
              (is (= "valid" (get-in result [:body "validationStatus"])))))
          (let [skipped (write (-> body (assoc "validate" false "rkey" "legacy") (update "record" dissoc "createdAt")))]
            (is (= 200 (:status skipped)))
            (is (not (contains? (:body skipped) "validationStatus"))))
          ;; Skip mode still enforces the data model, $type and blob ownership.
          (is (= 400 (:status (write (-> body (assoc "validate" false) (assoc-in ["record" "text"] 1.5))))))
          (is (= 400 (:status (write (-> body (assoc "validate" false) (assoc-in ["record" "$type"] "com.example.other"))))))
          (let [unknown (assoc body "collection" "com.example.newRecord" "record" {"$type" "com.example.newRecord"})]
            (is (= "unknown" (get-in (write unknown) [:body "validationStatus"])))
            (is (= 400 (:status (write (assoc unknown "validate" true)))))
            (is (= 200 (:status (write (assoc unknown "validate" false))))))
          (let [before (snapshot)
                batch {"repo" did "validate" true
                       "writes" [{"$type" "com.atproto.repo.applyWrites#create" "collection" "app.bsky.feed.post" "value" post}
                                 {"$type" "com.atproto.repo.applyWrites#create" "collection" "app.bsky.feed.post" "value" (dissoc post "text")}]}
                failed (api/xrpc client port "POST" "com.atproto.repo.applyWrites" batch token)]
            (is (= 400 (:status failed)))
            (is (= "InvalidRecord" (get-in failed [:body "error"])))
            (is (= before (snapshot)) "Earlier writes, blocks and events roll back with an invalid batch entry")
            (let [skipped (api/xrpc client port "POST" "com.atproto.repo.applyWrites" (assoc batch "validate" false) token)]
              (is (= 200 (:status skipped)))
              (is (every? #(not (contains? % "validationStatus")) (get-in skipped [:body "results"])))))))
      (finally ((:stop! server))))))
