(ns pds.process-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.crypto :as crypto]
            [pds.db-test :as fixture]
            [pds.http-test :as http-test])
  (:import [java.net.http HttpClient]
           [java.util.concurrent TimeUnit]))
(use-fixtures :each fixture/isolated-database)
(deftest documented-main-starts-and-stops
  (let [java (str (System/getProperty "java.home") "/bin/java")
        builder (ProcessBuilder. ^java.util.List [java "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" "pds.main"])
        env (.environment builder)]
    (doseq [name ["PDS_EMAIL_WORKER_URL" "PDS_EMAIL_WORKER_TOKEN" "PDS_EMAIL_FROM"]] (.remove env name))
    (.putAll env {"PDS_DATABASE_URL" (.getURL fixture/*ds*)
                 "PDS_DATABASE_USER" (.getUser fixture/*ds*) "PDS_DATABASE_PASSWORD" (.getPassword fixture/*ds*)
                 "PDS_MASTER_KEY" (crypto/b64 (crypto/random-bytes 32))
                 "PDS_HOST" "127.0.0.1" "PDS_PORT" "0" "PDS_HOSTNAME" "localhost"
                 "PDS_PUBLIC_URL" "http://localhost:3000" "PDS_USER_DOMAIN" "pds.localhost"
                 "PDS_ENABLE_SIGNUP" "false"})
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
          (when (:port result)
            (with-open [client (HttpClient/newHttpClient)]
              (is (= 200 (.statusCode (http-test/request client (:port result) "GET" "/xrpc/_health")))))))
        (finally
          (.destroy process)
          (is (.waitFor process 10 TimeUnit/SECONDS) "Shutdown hook completes")
          (when (.isAlive process) (.destroyForcibly process))
          (.close reader))))))
