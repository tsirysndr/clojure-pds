(ns pds.main
  (:require [pds.app :as app]
            [pds.config :as config]
            [pds.db :as db]
            [pds.http :as http]))

(defn -main [& _]
  (let [settings (config/load-config)
        ds (db/datasource (db/settings))
        _ (db/migrate! ds)
        {:keys [port stop!]} (http/start! settings (app/handler settings))
        stopped (promise)
        hook (Thread. ^Runnable (fn [] (stop!) (deliver stopped true)))
        runtime (Runtime/getRuntime)]
    (try
      (.addShutdownHook runtime hook)
      (println (str "clojure-pds " app/version " listening on " (:host settings) ":" port))
      @stopped
      (finally
        (stop!)
        (try (.removeShutdownHook runtime hook)
             (catch IllegalStateException _))))))
