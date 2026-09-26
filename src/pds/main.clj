(ns pds.main
  (:require [pds.app :as app]
            [pds.admin :as admin]
            [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.blob-cleanup :as blob-cleanup]
            [pds.config :as config]
            [pds.db :as db]
            [pds.email :as email]
            [pds.http :as http]
            [pds.firehose :as firehose]
            [pds.invites :as invites]
            [pds.identity :as identity]
            [pds.net :as net]
            [pds.plc-provision :as provision]
            [pds.redis :as redis]
            [pds.s3 :as s3]))

(defn -main [& _]
  (let [email-config (email/settings)
        blob-config (s3/settings (System/getenv))
        rate-config (redis/settings (System/getenv))
        settings (merge (config/load-config) (accounts/settings (System/getenv))
                        (admin/settings (System/getenv)) (invites/settings (System/getenv))
                        (firehose/settings (System/getenv))
                        (identity/settings (System/getenv))
                        (auth/settings (System/getenv)) {:email-enabled (boolean email-config)})
        ds (db/datasource (db/settings))
        _ (db/migrate! ds)
        blob-store (s3/open-store blob-config)
        limiter (try (redis/open-limiter rate-config)
                     (catch Throwable t
                       (when blob-store (.close ^java.io.Closeable blob-store))
                       (throw t)))
        http-client (try (net/open-client)
                         (catch Throwable t
                           (try (when blob-store (.close ^java.io.Closeable blob-store))
                                (finally (when (instance? java.io.Closeable limiter) (.close ^java.io.Closeable limiter))))
                           (throw t)))
        settings (assoc settings :blob-store blob-store :rate-limiter limiter :http-client http-client)
        dependencies-closed? (atom false)
        stop-dependencies! #(when (compare-and-set! dependencies-closed? false true)
                              (try (when blob-store (.close ^java.io.Closeable blob-store))
                                   (finally (try (when (instance? java.io.Closeable limiter)
                                                   (.close ^java.io.Closeable limiter))
                                                 (finally (.close ^java.io.Closeable http-client))))))]
    (try
      (let [stop-email! (email/start! ds email-config)
            stop-cleanup! (try (blob-cleanup/start! ds blob-store)
                               (catch Throwable t (stop-email!) (throw t)))
            stop-provision! (try (provision/start! #(accounts/provision-one! ds settings nil))
                                 (catch Throwable t (try (stop-email!) (finally (stop-cleanup!))) (throw t)))]
        (try
          (let [{:keys [port stop!]} (http/start! settings (app/handler settings ds))
                stopped (promise)
                once (atom false)
                stop-all! (fn [] (when (compare-and-set! once false true)
                                   (try (stop!)
                                        (finally (try (stop-provision!)
                                                      (finally (try (stop-email!)
                                                                    (finally (try (stop-cleanup!) (finally (stop-dependencies!)))))))))))
                hook (Thread. ^Runnable (fn [] (try (stop-all!) (finally (deliver stopped true)))))
                runtime (Runtime/getRuntime)]
            (try
              (.addShutdownHook runtime hook)
              (println (str "clojure-pds " app/version " listening on " (:host settings) ":" port))
              @stopped
              (finally
                (stop-all!)
                (try (.removeShutdownHook runtime hook) (catch IllegalStateException _)))))
          (catch Throwable e (try (stop-provision!) (finally (try (stop-email!) (finally (stop-cleanup!))))) (throw e))))
      (finally (stop-dependencies!)))))
