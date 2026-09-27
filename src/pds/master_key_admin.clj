(ns pds.master-key-admin
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [pds.db :as db]
            [pds.master-keys :as keys]))

(def usage "mise exec -- clojure -M:master-key status\nmise exec -- clojure -M:master-key register\nmise exec -- clojure -M:master-key rewrap EXPECTED_OLD_FINGERPRINT")
(defn run! [args env]
  (try
    (when-not (or (#{["status"] ["register"]} (vec args))
                  (and (= 2 (count args)) (= "rewrap" (first args)) (re-matches #"[A-Za-z0-9_-]{43}" (second args))))
      (throw (ex-info usage {:usage true})))
    (let [rotate? (= "rewrap" (first args))
          old (when (not= "status" (first args)) (keys/key! env "PDS_MASTER_KEY"))
          new (when rotate? (keys/key! env "PDS_NEW_MASTER_KEY"))
          ds (db/datasource (db/settings env))]
      (db/migrate! ds)
      {:exit 0 :result (case (first args)
                        "rewrap" (keys/rewrap! ds old new (second args))
                        "register" (with-open [_ (keys/open-lease! ds old)] (keys/status! ds))
                        "status" (keys/status! ds))})
    (catch Exception e
      {:exit (if (:usage (ex-data e)) 64 1)
       :result {:error (or (:master-key-error (ex-data e)) (when (:usage (ex-data e)) "Usage") "MasterKeyOperationFailed")
                :message (cond (:usage (ex-data e)) usage
                               (:master-key-error (ex-data e)) (.getMessage e)
                               :else "Master-key operation failed; inspect status before retrying")}})))
(defn -main [& args]
  (if (= ["--help"] (vec args)) (println usage)
    (let [{:keys [exit result]} (run! args (System/getenv))]
      (println (json/write-str result))
      (when-not (zero? exit) (System/exit exit)))))
