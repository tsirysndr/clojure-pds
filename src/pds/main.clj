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
            [pds.handles :as handles]
            [pds.firehose :as firehose]
            [pds.invites :as invites]
            [pds.identity :as identity]
            [pds.master-keys :as master-keys]
            [pds.net :as net]
            [pds.oauth.cleanup :as oauth-cleanup]
            [pds.plc-provision :as provision]
            [pds.proxy :as proxy]
            [pds.redis :as redis]
            [pds.relay :as relay]
            [pds.repo-export :as repo-export]
            [pds.s3 :as s3]))

(defn- run-server! [ds lease]
  (let [email-config (email/settings)
        blob-config (s3/settings (System/getenv))
        rate-config (redis/settings (System/getenv))
        settings (merge (config/load-config) (accounts/settings (System/getenv))
                        (admin/settings (System/getenv)) (invites/settings (System/getenv))
                        (firehose/settings (System/getenv))
                        (identity/settings (System/getenv))
                        (proxy/settings (System/getenv))
                        (relay/settings (System/getenv))
                        (repo-export/settings (System/getenv))
                        (blob-cleanup/settings (System/getenv))
                        (auth/settings (System/getenv)) {:email-enabled (boolean email-config)})
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
                                                 (finally (try (.close ^java.io.Closeable http-client)
                                                               (finally (try (.close ^java.io.Closeable ds)
                                                                             (finally (.close ^java.io.Closeable lease))))))))))]
    (try
      (let [stop-email! (email/start! ds email-config)
            stop-cleanup! (try
                            (let [stop-blobs! (blob-cleanup/start! ds blob-store settings)]
                              (try
                                (let [stop-oauth! (oauth-cleanup/start! ds)]
                                  #(try (stop-oauth!) (finally (stop-blobs!))))
                                (catch Throwable t (stop-blobs!) (throw t))))
                            (catch Throwable t (stop-email!) (throw t)))
            stop-provision! (try (provision/start! #(do (accounts/provision-one! ds settings nil)
                                                       (handles/process-one! ds settings nil)))
                                 (catch Throwable t (try (stop-email!) (finally (stop-cleanup!))) (throw t)))]
        (try
          (let [{:keys [port stop!]} (http/start! settings (app/handler settings ds))
                stop-relay! (try (relay/start! ds settings) (catch Throwable t (stop!) (throw t)))
                stopped (promise)
                once (atom false)
                stop-all! (fn [] (when (compare-and-set! once false true)
                                   (try (try (stop-relay!) (finally (stop!)))
                                        (finally (try (stop-provision!)
                                                      (finally (try (stop-email!)
                                                                    (finally (try (stop-cleanup!) (finally (stop-dependencies!)))))))))))
                hook (Thread. ^Runnable (fn [] (try (stop-all!) (finally (deliver stopped true)))))
                runtime (Runtime/getRuntime)]
            (try
              (.addShutdownHook runtime hook)
              (println (str app/version " listening on " (:host settings) ":" port))
              (loop []
                (when (= ::check (deref stopped 1000 ::check))
                  (when-not (master-keys/live? lease)
                    (throw (ex-info "Master-key maintenance lease lost; shutting down" {})))
                  (recur)))
              (finally
                (stop-all!)
                (try (.removeShutdownHook runtime hook) (catch IllegalStateException _)))))
          (catch Throwable e (try (stop-provision!) (finally (try (stop-email!) (finally (stop-cleanup!))))) (throw e))))
      (finally (stop-dependencies!)))))

(defn -main [& _]
  ;; Covers configuration, migration and dependency startup failures. The
  ;; shutdown hook also closes the pool, after HTTP and background workers,
  ;; because the JVM need not wait for this main thread after hooks finish.
  (with-open [ds (db/open-pool! (db/settings) (db/pool-settings))]
    (db/migrate! ds)
    (with-open [lease (master-keys/open-lease! (db/datasource (db/settings))
                                             (:master-key (auth/settings (System/getenv))))]
      (run-server! ds lease))))
