(ns pds.firehose-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.events :as events]
            [pds.firehose :as firehose]
            [pds.http :as http]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.server-api-test :as api]
            [pds.sync-api-test :as sync-test]
            [pds.websocket-test :as ws])
  (:import [java.net.http HttpClient WebSocket]
           [java.util.concurrent TimeUnit LinkedBlockingQueue]))

(use-fixtures :each fixture/isolated-database)
(def path "/xrpc/com.atproto.sync.subscribeRepos")
(defn read! [connection] (some-> (ws/receive connection) (codec/decode-pair 5000000)))
(defn stop! [connection] (.abort ^WebSocket (:socket connection)))

(deftest live-stream-replay-and-account-visibility
  (let [settings (api/settings)
        account (accounts/create! fixture/*ds* settings {"handle" "stream.example.com" "email" "stream@example.com" "password" "test-password"})
        did (:did account)
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [connection (ws/connect client (:port server) (str path "?cursor=0"))]
          (try
            (let [raw (vec (repeatedly 3 #(ws/receive connection))) frames (mapv #(codec/decode-pair % 5000000) raw)
                  seqs (mapv #(get (second %) "seq") frames) cursor (last seqs)]
              (is (= ["#identity" "#account" "#commit"] (mapv #(get (first %) "t") frames)))
              (is (apply < seqs))
              (with-open [conn (db/connection fixture/*ds*)]
                (let [key (:public_key (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" did)))]
                  (sync-test/verify-upstream! "verify-stream.mjs" {:did did :didKey (str "did:key:" (crypto/multikey "ES256" key))
                                                                 :frames (mapv crypto/b64 raw)})))
              (stop! connection)
              (db/transact! fixture/*ds* #(repo/apply-writes! % settings did [{:action :create :collection "com.example.record" :rkey "one" :value {"$type" "com.example.record"}}] nil))
              (let [resumed (ws/connect client (:port server) (str path "?cursor=" cursor))]
                (try
                  (let [[header event] (read! resumed)]
                    (is (= "#commit" (get header "t"))))
                  ;; Live delivery after reconnect; application frames are ignored.
                  (.join (.sendText ^WebSocket (:socket resumed) "ignored" true))
                  (is (= 200 (:status (api/xrpc client (:port server) "POST" "com.atproto.server.deactivateAccount" {} (:accessJwt account)))))
                  (let [[header event] (read! resumed)]
                    (is (= "#account" (get header "t")))
                    (is (= "deactivated" (get event "status"))))
                  (finally (stop! resumed))))
              ;; Replay does not expose previously committed content while inactive.
              (let [inactive (ws/connect client (:port server) (str path "?cursor=0"))]
                (try
                  (is (= ["#identity" "#account" "#account"] (mapv (fn [_] (get (first (read! inactive)) "t")) (range 3))))
                  (is (nil? (.poll ^LinkedBlockingQueue (:messages inactive) 500 TimeUnit/MILLISECONDS)))
                  (finally (stop! inactive))))
              (is (= 426 (:status (api/call client (:port server) "GET" path nil nil))))
              (is (= 405 (:status (api/call client (:port server) "HEAD" path nil nil)))))
            (finally (stop! connection)))))
      (finally ((:stop! server))))))

(deftest cursor-errors-window-and-connection-limits
  (let [settings (assoc (api/settings) :firehose-max-clients 1)
        account (accounts/create! fixture/*ds* settings {"handle" "cursor.example.com" "email" "cursor@example.com" "password" "test-password"})
        _ (db/transact! fixture/*ds* #(db/execute! % "UPDATE repo_events SET created_at = now() - interval '2 days'"))
        _ (db/transact! fixture/*ds* #(events/account! % (:did account) "active"))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (doseq [[cursor error] [["9999999" "FutureCursor"] ["-1" "InvalidRequest"] ["abc" "InvalidRequest"]]]
          (let [connection (ws/connect client (:port server) (str path "?cursor=" cursor))]
            (try
              (let [[header event] (read! connection)]
                (is (= -1 (get header "op"))) (is (= error (get event "error"))))
              (is (map? (deref (:closed connection) 5000 nil)))
              (finally (stop! connection)))))
        (let [connection (ws/connect client (:port server) (str path "?cursor=1"))]
          (try
            (is (= "OutdatedCursor" (get (second (read! connection)) "name")))
            (is (= "#account" (get (first (read! connection)) "t")))
            (let [extra (ws/connect client (:port server) path)]
              (try (is (= "ConsumerTooSlow" (get (second (read! extra)) "error")))
                   (finally (stop! extra))))
            (finally (stop! connection)))))
      (finally ((:stop! server))))))

(deftest slow-consumer-budget-is-bounded
  (let [settings (merge (firehose/settings {}) {:firehose-max-backlog 1})
        account (accounts/create! fixture/*ds* (api/settings) {"handle" "slow.example.com" "email" "slow@example.com" "password" "test-password"})
        ds fixture/*ds* sent (atom 0)]
    (is (= "ConsumerTooSlow"
           (try (firehose/stream! ds settings {:query-string "cursor=0"}
                  {:open? (constantly true) :ping! (fn [])
                   :send! (fn [_] (when (= 1 (swap! sent inc))
                                   (db/transact! ds #(do (events/account! % (:did account) "active")
                                                        (events/account! % (:did account) "active")))))})
                nil (catch clojure.lang.ExceptionInfo e (:stream-error (ex-data e))))))))

(deftest current-position-and-restart-replay
  (let [settings (api/settings) ds fixture/*ds*
        account (accounts/create! ds settings {"handle" "restart.example.com" "email" "restart@example.com" "password" "test-password"})
        did (:did account)
        handler (app/handler settings ds)
        server (http/start! settings handler)
        checkpoint (:high (firehose/bounds ds java.time.Instant/EPOCH))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [connection (ws/connect client (:port server) path)]
          (try
            (is (nil? (.poll ^LinkedBlockingQueue (:messages connection) 300 TimeUnit/MILLISECONDS)))
            (db/transact! ds #(events/account! % did "active"))
            (is (> (get (second (read! connection)) "seq") checkpoint))
            (finally (stop! connection))))
        ((:stop! server))
        (let [restarted (http/start! (assoc settings :port (:port server)) handler)]
          (try
            (let [connection (ws/connect client (:port restarted) (str path "?cursor=" checkpoint))]
              (try (is (= "#account" (get (first (read! connection)) "t")))
                   (finally (stop! connection))))
            (finally ((:stop! restarted))))))
      (finally ((:stop! server))))))
