(ns pds.main
  (:require [pds.app :as app]
            [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.config :as config]
            [pds.db :as db]
            [pds.email :as email]
            [pds.http :as http]
            [pds.redis :as redis]
            [pds.s3 :as s3]))

(defn -main [& _]
  (let [email-config (email/settings)
        blob-config (s3/settings (System/getenv))
        rate-config (redis/settings (System/getenv))
        settings (merge (config/load-config) (accounts/settings (System/getenv))
                        (auth/settings (System/getenv)) {:email-enabled (boolean email-config)})
        ds (db/datasource (db/settings))
        _ (db/migrate! ds)
        blob-store (s3/open-store blob-config)
        limiter (try (redis/open-limiter rate-config)
                     (catch Throwable t
                       (when blob-store (.close ^java.io.Closeable blob-store))
                       (throw t)))
        settings (assoc settings :blob-store blob-store :rate-limiter limiter)
        dependencies-closed? (atom false)
        stop-dependencies! #(when (compare-and-set! dependencies-closed? false true)
                              (try (when blob-store (.close ^java.io.Closeable blob-store))
                                   (finally (when (instance? java.io.Closeable limiter)
                                              (.close ^java.io.Closeable limiter)))))]
    (try
      (let [stop-email! (email/start! ds email-config)]
        (try
          (let [{:keys [port stop!]} (http/start! settings (app/handler settings ds))
                stopped (promise)
                once (atom false)
                stop-all! (fn [] (when (compare-and-set! once false true)
                                   (try (stop!)
                                        (finally (try (stop-email!) (finally (stop-dependencies!)))))))
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
      (finally (stop-dependencies!)))))
