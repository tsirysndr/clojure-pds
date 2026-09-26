(ns pds.main
  (:require [pds.app :as app]
            [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.config :as config]
            [pds.db :as db]
            [pds.email :as email]
            [pds.http :as http]
            [pds.s3 :as s3]))

(defn -main [& _]
  (let [email-config (email/settings)
        blob-config (s3/settings (System/getenv))
        settings (merge (config/load-config) (accounts/settings (System/getenv))
                        (auth/settings (System/getenv)) {:email-enabled (boolean email-config)})
        ds (db/datasource (db/settings))
        _ (db/migrate! ds)
        blob-store (s3/open-store blob-config)
        settings (assoc settings :blob-store blob-store)
        blob-closed? (atom false)
        stop-blob! #(when (and blob-store (compare-and-set! blob-closed? false true))
                      (.close ^java.io.Closeable blob-store))]
    (try
      (let [stop-email! (email/start! ds email-config)]
        (try
          (let [{:keys [port stop!]} (http/start! settings (app/handler settings ds))
                stopped (promise)
                once (atom false)
                stop-all! (fn [] (when (compare-and-set! once false true)
                                   (try (stop!)
                                        (finally (try (stop-email!) (finally (stop-blob!)))))))
                hook (Thread. ^Runnable (fn [] (try (stop-all!) (finally (deliver stopped true)))))
                runtime (Runtime/getRuntime)]
            (try
              (.addShutdownHook runtime hook)
              (println (str "clojure-pds " app/version " listening on " (:host settings) ":" port))
              @stopped
              (finally
                (stop-all!)
                (try (.removeShutdownHook runtime hook) (catch IllegalStateException _)))))
          (catch Throwable e (stop-email!) (throw e))))
      (finally (stop-blob!)))))
