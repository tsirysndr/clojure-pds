(ns pds.plc-recovery-admin
  "Recovery works even when the PDS database or its private keys are unavailable."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [pds.identity :as identity]
            [pds.net :as net]
            [pds.plc-recovery :as recovery]
            [pds.request :as request]))

(def usage "mise exec -- clojure -M:plc-recovery inspect DID SIGNED_OPERATION.json\nmise exec -- clojure -M:plc-recovery submit DID EXPECTED_REMOTE_CID EXPECTED_OPERATION_CID SIGNED_OPERATION.json")
(defn command! [args]
  (let [[command did remote operation] args]
    (when-not (or (and (= "inspect" command) (= 3 (count args)))
                  (and (= "submit" command) (= 5 (count args))))
      (throw (ex-info usage {:usage true})))
    (if (= "submit" command) (recovery/identifiers! did remote operation) (recovery/identifiers! did))
    {:command command :did did :remote remote :operation operation :file (last args)}))
(defn read-operation! [file]
  (try
    (with-open [input (io/input-stream (io/file file))]
      (recovery/operation! (request/json-value (request/body-bytes {:body input} 65536))))
    (catch Exception _
      (throw (ex-info "Cannot read signed PLC operation" {:type :plc-recovery :reason :invalid-operation-file :retryable false})))))
(defn execute! [client origin {:keys [command did remote operation]} op]
  (case command
    "inspect" (recovery/inspect! client origin did op)
    "submit" (recovery/submit! client origin did remote operation op)))
(defn run! [args env]
  (try
    (let [parsed (command! args) op (read-operation! (:file parsed))
          origin (identity/origin! (get env "PDS_PLC_URL" "https://plc.directory"))]
      (with-open [client (net/open-client)]
        (let [result (execute! client origin parsed op)]
          {:exit (if (= "superseded" (:state result)) 1 0) :result result})))
    (catch Exception e
      (let [data (ex-data e) known? (#{:plc-directory :plc-recovery} (:type data))]
        {:exit (cond (:usage data) 64 (and known? (:retryable data)) 2 :else 1)
         :result {:error (if (:usage data) "Usage" "PlcRecoveryFailed")
                  :reason (if known? (name (:reason data)) "invalid-input")
                  :message (if (:usage data) usage "Recovery was not confirmed; inspect the same signed operation before retrying")}}))))
(defn -main [& args]
  (if (= ["--help"] (vec args)) (println usage)
    (let [{:keys [exit result]} (run! args (System/getenv))]
      (println (json/write-str result))
      (when-not (zero? exit) (System/exit exit)))))
