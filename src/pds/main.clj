(ns pds.main
  (:require [pds.app :as app]
            [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.config :as config]
            [pds.db :as db]
            [pds.email :as email]
            [pds.http :as http]))

(defn -main [& _]
  (let [email-config (email/settings)
        settings (merge (config/load-config) (accounts/settings (System/getenv))
                        (auth/settings (System/getenv)) {:email-enabled (boolean email-config)})
        ds (db/datasource (db/settings))
        _ (db/migrate! ds)
        stop-email! (email/start! ds email-config)]
    (try
      (let [{:keys [port stop!]} (http/start! settings (app/handler settings ds))
            stopped (promise)
            once (atom false)
            stop-all! (fn [] (when (compare-and-set! once false true)
                               (try (stop!) (finally (stop-email!)))))
            hook (Thread. ^Runnable (fn [] (stop-all!) (deliver stopped true)))
            runtime (Runtime/getRuntime)]
        (try
          (.addShutdownHook runtime hook)
          (println (str "clojure-pds " app/version " listening on " (:host settings) ":" port))
          @stopped
          (finally
            (stop-all!)
            (try (.removeShutdownHook runtime hook) (catch IllegalStateException _)))))
      (catch Throwable e (stop-email!) (throw e)))))
