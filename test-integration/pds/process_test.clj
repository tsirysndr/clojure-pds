(ns pds.process-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.master-keys :as master-keys]
            [pds.http-test :as http-test])
  (:import [java.net.http HttpClient]
           [java.util.concurrent TimeUnit]))
(use-fixtures :each fixture/isolated-database)
(defn process-lifecycle! [lose-lease?]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        builder (ProcessBuilder. ^java.util.List [java "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" "pds.main"])
        env (.environment builder)]
    (doseq [name ["PDS_EMAIL_WORKER_URL" "PDS_EMAIL_WORKER_TOKEN" "PDS_EMAIL_FROM" "PDS_RELAY_URLS" "PDS_RELAY_INTERVAL_SECONDS"]] (.remove env name))
    (.putAll env (merge (fixture/database-env)
                {"PDS_DB_POOL_SIZE" "2" "PDS_DB_POOL_TIMEOUT_MS" "1000"
                 "PDS_MASTER_KEY" (crypto/b64 (crypto/random-bytes 32))
                 "PDS_HOST" "127.0.0.1" "PDS_PORT" "0" "PDS_HOSTNAME" "localhost"
                 "PDS_PUBLIC_URL" "http://localhost:3000" "PDS_USER_DOMAIN" "pds.localhost"
                 "PDS_DID_METHOD" "web" "PDS_PLC_URL" "https://plc.directory"
                 "PDS_ENABLE_SIGNUP" "false"}))
    (.putAll env (if-let [endpoint (System/getenv "PDS_TEST_S3_ENDPOINT")]
                   {"PDS_BLOB_BACKEND" "s3" "PDS_S3_ENDPOINT" endpoint "PDS_S3_BUCKET" "startup-test"
                    "PDS_S3_ACCESS_KEY_ID" "test-access" "PDS_S3_SECRET_ACCESS_KEY" "test-secret"
                    "PDS_S3_REGION" "us-east-1" "PDS_S3_FORCE_PATH_STYLE" "true" "PDS_S3_PREFIX" "blobs"}
                   {"PDS_BLOB_BACKEND" "postgres"}))
    (.putAll env (if-let [url (System/getenv "PDS_TEST_REDIS_URL")]
                   {"PDS_RATE_LIMIT_BACKEND" "redis" "PDS_REDIS_URL" url "PDS_REDIS_PREFIX" "startup-test"}
                   {"PDS_RATE_LIMIT_BACKEND" "memory"}))
    (.put env "PDS_RATE_LIMIT_REQUESTS" "120")
    (.put env "PDS_RATE_LIMIT_WINDOW_SECONDS" "60")
    (.remove env "PDS_S3_SESSION_TOKEN")
    (.redirectErrorStream builder true)
    (let [process (.start builder)
          reader (io/reader (.getInputStream process))
          ready (future
                  (loop [lines []]
                    (if-let [line (.readLine reader)]
                      (if-let [[_ port] (re-find #"listening on 127\.0\.0\.1:([0-9]+)" line)]
                        {:port (Long/parseLong port)} (recur (conj lines line)))
                      {:error lines})))]
      (try
        (let [result (deref ready 20000 :timeout)]
          (is (map? result))
          (is (pos-int? (:port result)) (pr-str result))
          (is (= "ready" (:state (master-keys/status! fixture/*ds*))))
          (let [key (crypto/unb64 (.get env "PDS_MASTER_KEY"))]
            (is (= "PdsRunning"
                   (try (master-keys/rewrap! fixture/*ds* key (crypto/random-bytes 32) (master-keys/fingerprint key))
                        (catch clojure.lang.ExceptionInfo e (:master-key-error (ex-data e)))))))
          (when (:port result)
            (with-open [client (HttpClient/newHttpClient)]
              (is (= 200 (.statusCode (http-test/request client (:port result) "GET" "/xrpc/_health"))))))
          (when lose-lease?
            (with-open [conn (db/connection fixture/*ds*)]
              (is (= [true] (mapv :stopped (db/query conn "SELECT pg_terminate_backend(pid) AS stopped FROM pg_stat_activity
                                                          WHERE datname = current_database() AND application_name = 'clojure-pds/master-key-lease'")))))
            (is (.waitFor process 15 TimeUnit/SECONDS) "Lost maintenance lease stops HTTP and workers")
            (when-not (.isAlive process) (is (not (zero? (.exitValue process)))))))
        (finally
          (.destroy process)
          (is (.waitFor process 10 TimeUnit/SECONDS) "Shutdown hook completes")
          (when (.isAlive process) (.destroyForcibly process))
          (.close reader))))))

(deftest documented-main-starts-and-stops (process-lifecycle! false))
(deftest server-shuts-down-when-its-maintenance-lease-is-lost (process-lifecycle! true))
