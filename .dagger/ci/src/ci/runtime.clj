(ns ci.runtime
  "Session lifecycle and complete engine diagnostics for CI logs."
  (:require [dagger.core :as dag]
            [dagger.modules :as modules]))

(defn report! [error]
  (binding [*out* *err*]
    (println "Dagger pipeline failed:" (ex-message error))
    (doseq [extension (->> (take-while some? (iterate ex-cause error))
                           (mapcat #(get (ex-data %) :errors))
                           (map #(get % "extensions"))
                           (filter #(= "EXEC_ERROR" (get % "_type")))
                           distinct)]
      (println "Command:" (pr-str (get extension "cmd")))
      (println "Exit code:" (get extension "exitCode"))
      (doseq [stream ["stdout" "stderr"]
              :let [output (get extension stream)]
              :when (and (string? output) (seq output))]
        (println (str "--- " stream " ---"))
        ;; Print strings directly: object/exception printers can omit long tails.
        (print output)
        (println)))
    (flush)))

(defn serve! [definition]
  (try
    (with-open [client (dag/connect {:timeout-ms 3600000})]
      (modules/serve-with-client! client definition))
    (catch Throwable error
      (report! error)
      (throw error))))
