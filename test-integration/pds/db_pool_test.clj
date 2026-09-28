(ns pds.db-pool-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.auth :as auth]
            [pds.blob-cleanup :as blob-cleanup]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.email :as email]
            [pds.http :as http]
            [pds.main :as main]
            [pds.master-keys :as master-keys]
            [pds.net :as net]
            [pds.oauth.cleanup :as oauth-cleanup]
            [pds.plc-provision :as provision]
            [pds.redis :as redis]
            [pds.relay :as relay]
            [pds.s3 :as s3]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]
           [java.sql Connection SQLException SQLTransientConnectionException]))

(use-fixtures :each fixture/isolated-database)

(defn database-settings []
  fixture/*database*)

(defn pool [size]
  (db/open-pool! (database-settings) {:maximum-size size :timeout-ms 500}))

(defn scalar [conn sql] (-> (db/query conn sql) first vals first))

(deftest connection-startup-failures-do-not-expose-driver-details
  (let [failure (try
                  (with-open [_ (db/open-pool! (assoc (database-settings) :user "missing-secret-pool-user")
                                              {:maximum-size 1 :timeout-ms 500})]
                    nil)
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (= "Unable to connect to the configured database" (some-> failure .getMessage)))
    (is (= {} (ex-data failure)))
    (is (nil? (some-> failure .getCause)))))

(deftest bounded-acquisition-reuse-and-close
  (let [ds (pool 2)]
    (try
      (with-open [a (db/connection ds) b (db/connection ds)]
        (is (not= (scalar a "SELECT pg_backend_pid()") (scalar b "SELECT pg_backend_pid()")))
        (let [start (System/nanoTime)]
          (is (thrown? SQLTransientConnectionException (db/connection ds)))
          (is (<= 400 (/ (- (System/nanoTime) start) 1000000.0) 3000)))
        (is (= 2 (.getTotalConnections (.getHikariPoolMXBean ds)))))
      (with-open [c (db/connection ds)] (is (= 1 (scalar c "SELECT 1"))))
      (finally (.close ds)))
    (is (.isClosed ds))
    (is (thrown? SQLException (db/connection ds)))
    (with-open [reopened (pool 1)]
      (is (true? (db/migrate! reopened))))))

(deftest returned-connections-do-not-leak-transactions-or-jdbc-state
  (with-open [ds (pool 1)]
    (let [pid (with-open [c (db/connection ds)]
                (db/execute! c "CREATE TABLE pool_values (n integer PRIMARY KEY)")
                (scalar c "SELECT pg_backend_pid()"))]
      ;; Closing a borrowed connection without commit must roll back.
      (with-open [c (db/connection ds)]
        (.setAutoCommit c false)
        (db/execute! c "INSERT INTO pool_values VALUES (1)")
        (db/execute! c "SET LOCAL statement_timeout = '1s'"))
      (with-open [c (db/connection ds)]
        (is (= pid (scalar c "SELECT pg_backend_pid()")) "Same physical connection is reused")
        (is (.getAutoCommit c))
        (is (= 0 (scalar c "SELECT count(*) FROM pool_values")))
        (is (= "0" (scalar c "SHOW statement_timeout")))
        (.setReadOnly c true)
        (.setTransactionIsolation c Connection/TRANSACTION_SERIALIZABLE))
      (with-open [c (db/connection ds)]
        (is (false? (.isReadOnly c)))
        (is (= Connection/TRANSACTION_READ_COMMITTED (.getTransactionIsolation c))))
      (is (thrown? SQLException
                   (db/transact! ds (fn [c]
                                      (db/execute! c "INSERT INTO pool_values VALUES (2)")
                                      (db/execute! c "INSERT INTO pool_values VALUES (2)")))))
      (db/transact! ds #(db/execute! % "INSERT INTO pool_values VALUES (3)"))
      (with-open [c (db/connection ds)]
        (is (= [{:n 3}] (db/query c "SELECT * FROM pool_values")))))))

(deftest concurrent-transactions-and-broken-connection-replacement
  (with-open [ds (pool 2)]
    (db/transact! ds #(db/execute! % "CREATE TABLE pool_counter (n integer); INSERT INTO pool_counter VALUES (0)"))
    (let [jobs (mapv (fn [_] (future (db/transact! ds
                                     (fn [c]
                                       (db/execute! c "UPDATE pool_counter SET n = n + 1")
                                       (scalar c "SELECT pg_backend_pid()"))))) (range 16))
          pids (mapv #(deref % 5000 :timeout) jobs)]
      (is (every? integer? pids))
      (is (<= (count (set pids)) 2))
      (with-open [c (db/connection ds)] (is (= 16 (scalar c "SELECT n FROM pool_counter"))))))
  (with-open [ds (pool 1)]
    (let [pid (with-open [c (db/connection ds)] (scalar c "SELECT pg_backend_pid()"))]
      (is (thrown? SQLException
                   (db/transact! ds #(db/query % "SELECT pg_terminate_backend(pg_backend_pid())"))))
      (with-open [c (db/connection ds)]
        (is (not= pid (scalar c "SELECT pg_backend_pid()")))
        (is (= 1 (scalar c "SELECT 1")))))))

(deftest pooled-account-and-record-requests-over-http
  (with-open [ds (pool 1) client (HttpClient/newHttpClient)]
    (let [settings (api/settings)
          server (http/start! settings (app/handler settings ds))
          port (:port server)]
      (try
        (let [created (api/xrpc client port "POST" "com.atproto.server.createAccount"
                                {"handle" "pooled.example.com" "email" "pooled@example.com" "password" "correct-password"} nil)
              token (get-in created [:body "accessJwt"])
              did (get-in created [:body "did"])
              body {"repo" did "collection" "com.example.post" "rkey" "one"
                    "record" {"$type" "com.example.post" "text" "pooled"}}]
          (is (= 200 (:status created)))
          (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.repo.createRecord" body token))))
          (is (= 400 (:status (api/xrpc client port "POST" "com.atproto.repo.createRecord" body token))))
          (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.repo.putRecord" body token))))
          (with-open [_ (db/connection ds)]
            (let [response (api/xrpc client port "GET" "com.atproto.server.getSession" nil token)]
              (is (= 503 (:status response)))
              (is (= "ServiceUnavailable" (get-in response [:body "error"])))))
          (is (= 200 (:status (api/xrpc client port "GET" "com.atproto.server.getSession" nil token)))))
        (finally ((:stop! server)))))))

(deftest startup-failures-close-pool-and-started-dependencies
  (doseq [stage [:migration :lease :blob :redis :net :email :blob-cleanup :oauth-cleanup :provision :http :relay]]
    (let [ds (pool 1) opened (atom []) closed (atom [])
          start (fn [name result]
                  (if (= stage name) (throw (ex-info "startup-failure" {}))
                      (do (swap! opened conj name) result)))
          stop (fn [name] #(do (is (false? (.isClosed ds)) "Pool closes after its users")
                               (is (not (some #{:lease} @closed)) "Maintenance lease outlives workers")
                               (swap! closed conj name)))
          resource (fn [name] (reify java.io.Closeable (close [_] ((stop name)))))]
      (with-redefs [db/open-pool! (fn [& _] ds)
                    db/migrate! (fn [_] (when (= stage :migration) (throw (ex-info "startup-failure" {}))))
                    master-keys/open-lease! (fn [& _]
                                              (start :lease
                                                     (let [closed? (atom false)]
                                                       (reify java.io.Closeable
                                                         (close [_]
                                                           (when (compare-and-set! closed? false true)
                                                             (swap! closed conj :lease)))))))
                    auth/settings (fn [_] {})
                    email/settings (fn [] nil)
                    s3/open-store (fn [_] (start :blob (resource :blob)))
                    redis/open-limiter (fn [_] (start :redis (resource :redis)))
                    net/open-client (fn [] (start :net (resource :net)))
                    email/start! (fn [& _] (start :email (stop :email)))
                    blob-cleanup/start! (fn [& _] (start :blob-cleanup (stop :blob-cleanup)))
                    oauth-cleanup/start! (fn [& _] (start :oauth-cleanup (stop :oauth-cleanup)))
                    provision/start! (fn [& _] (start :provision (stop :provision)))
                    app/handler (fn [& _] identity)
                    http/start! (fn [& _] (start :http {:port 0 :stop! (stop :http)}))
                    relay/start! (fn [& _] (start :relay (stop :relay)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"startup-failure" (main/-main)) (name stage)))
      (is (.isClosed ds) (name stage))
      (is (= (frequencies @opened) (frequencies @closed)) (name stage)))))
