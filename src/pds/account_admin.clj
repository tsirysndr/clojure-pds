(ns pds.account-admin
  "Local operator recovery; authority is database access, never a user session."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [pds.admin-accounts :as accounts]
            [pds.db :as db]
            [pds.protocol.syntax :as syntax]))

(def usage "mise exec -- clojure -M:account-admin status DID\nmise exec -- clojure -M:account-admin recover-authenticators DID EXPECTED_SECURITY_VERSION REFERENCE")
(defn command! [args]
  (let [[command did expected reference] args
        version (try (when expected (Long/parseLong expected)) (catch Exception _ nil))]
    (when-not (and (syntax/did? did)
                   (or (and (= command "status") (= 2 (count args)))
                       (and (= command "recover-authenticators") (= 4 (count args))
                            version (<= 0 version) (re-matches #"[0-9]+" expected)
                            (accounts/recovery-reference? reference))))
      (throw (ex-info usage {:usage true})))
    {:command command :did did :version version :reference reference}))
(defn run! [args env]
  (try
    (let [{:keys [command did version reference]} (command! args)
          password (get env "PDS_RECOVERY_PASSWORD")]
      (when (and (= command "recover-authenticators") (not (and (string? password) (<= 8 (count password) 1024))))
        (throw (ex-info "PDS_RECOVERY_PASSWORD must contain 8 to 1024 characters" {:password-config true})))
      (with-open [ds (db/open-pool! (db/settings env) (db/pool-settings env))]
        (db/migrate! ds)
        {:exit 0 :result (db/transact! ds
                          #(case command
                             "status" (accounts/recovery-status! % did)
                             "recover-authenticators" (accounts/recover-authenticators! % did version reference password)))}))
    (catch Exception e
      {:exit (if (:usage (ex-data e)) 64 1)
       :result {:error (or (:error (ex-data e)) (when (:usage (ex-data e)) "Usage")
                           (when (:password-config (ex-data e)) "InvalidPasswordConfiguration") "AccountRecoveryFailed")
                :message (cond (:usage (ex-data e)) usage
                               (or (:xrpc (ex-data e)) (:password-config (ex-data e))) (.getMessage e)
                               :else "Account recovery failed; inspect status before retrying")}})))
(defn -main [& args]
  (if (= ["--help"] (vec args)) (println usage)
    (let [{:keys [exit result]} (run! args (System/getenv))]
      (println (json/write-str result))
      (when-not (zero? exit) (System/exit exit)))))
