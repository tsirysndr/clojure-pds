(ns pds.identity-admin
  "Local operator CLI; authority comes from access to database and master-key configuration."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.config :as config]
            [pds.db :as db]
            [pds.handles :as handles]
            [pds.master-keys :as master-keys]
            [pds.net :as net]
            [pds.plc-keys :as keys]
            [pds.plc-reconcile :as reconcile]
            [pds.signing-keys :as signing-keys]))

(def usage "mise exec -- clojure -M:identity status DID\nmise exec -- clojure -M:identity rotate-plc-key DID EXPECTED_OPERATION_CID\nmise exec -- clojure -M:identity rotate-signing-key DID EXPECTED_SIGNING_DID_KEY\nmise exec -- clojure -M:identity inspect-plc DID\nmise exec -- clojure -M:identity reconcile-plc DID LOCAL_CID QUEUED_CID_OR_- REMOTE_CID")
(defn command! [args]
  (let [[command did expected queued remote] args]
    (when-not (or (and (#{"status" "inspect-plc"} command) (= 2 (count args)))
                  (and (= "reconcile-plc" command) (= 5 (count args)))
                  (and (#{"rotate-plc-key" "rotate-signing-key"} command) (= 3 (count args))))
      (throw (ex-info usage {:usage true})))
    (case command
      "reconcile-plc" (reconcile/identifiers! did expected queued remote)
      ("rotate-plc-key" "inspect-plc") (keys/identifiers! did expected)
      (signing-keys/identifiers! did expected))
    (cond-> {:command command :did did :expected expected}
      (= "reconcile-plc" command) (assoc :queued queued :remote remote))))

(defn execute! [ds settings {:keys [command did expected queued remote]}]
  (case command
    "status" (signing-keys/status! ds did)
    "inspect-plc" (reconcile/inspect! ds settings did)
    "reconcile-plc" (reconcile/reconcile! ds settings did expected queued remote)
    "rotate-signing-key"
    (let [queued (signing-keys/enqueue! ds settings did expected)]
      (when-not (= "completed" (:state queued)) (handles/process-one! ds settings did))
      (signing-keys/result! ds did expected))
    "rotate-plc-key"
    (let [queued (keys/enqueue! ds settings did expected)]
      (when-not (= "completed" (:state queued)) (handles/process-one! ds settings did))
      (keys/result! ds did expected))))

(defn run! [args env]
  (try
    (let [{:keys [command] :as parsed} (command! args)]
      (with-open [ds (db/open-pool! (db/settings env) (db/pool-settings env))]
        (db/migrate! ds)
        (let [result (cond
                       (= "status" command) (execute! ds {} parsed)
                       (= "inspect-plc" command)
                       (with-open [client (net/open-client)]
                         (execute! ds (merge (config/load-config env) (accounts/settings env) {:http-client client}) parsed))
                       :else
                       (with-open [lease (master-keys/open-lease! (db/datasource (db/settings env)) (master-keys/key! env "PDS_MASTER_KEY"))
                                  client (net/open-client)]
                        (execute! ds (merge (config/load-config env) (accounts/settings env) (auth/settings env)
                                            {:http-client client}) parsed)))]
          {:exit (case (:state result) "failed" 1 ("pending" "working") 2 0) :result result})))
    (catch Exception e
      ;; Never serialize driver/network failures, environment values or sealed keys.
      {:exit (if (:usage (ex-data e)) 64 1)
       :result {:error (or (:error (ex-data e)) (when (:usage (ex-data e)) "Usage") "IdentityOperationFailed")
                :message (cond (:usage (ex-data e)) usage
                               (:xrpc (ex-data e)) (.getMessage e)
                               :else "Identity operation failed; inspect status before retrying")}})))

(defn -main [& args]
  (if (= ["--help"] (vec args)) (println usage)
    (let [{:keys [exit result]} (run! args (System/getenv))]
      (println (json/write-str result))
      (when-not (zero? exit) (System/exit exit)))))
