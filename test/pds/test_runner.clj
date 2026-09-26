(ns pds.test-runner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [pds.app :as app]
            [pds.output-conformance :as conformance]))

(defn -main [& roots]
  (let [namespaces (->> (mapcat #(file-seq (io/file %)) (or (seq roots) ["test"]))
                        (filter #(.isFile %))
                        (map #(.getPath %))
                        (filter #(str/ends-with? % "_test.clj"))
                        (map #(-> %
                                  (str/replace #"^(?:test|test-integration)/|\.clj$" "")
                                  (str/replace "/" ".")
                                  (str/replace "_" "-")
                                  symbol))
                        sort)]
    (doseq [n namespaces] (require n))
    (let [integration? (some #{"test-integration"} roots)
          original app/handler
          _ (reset! conformance/observations [])
          {:keys [fail error]}
          (with-redefs [app/handler (fn [& args]
                                     (let [handler (apply original args)]
                                       (if (and integration? (second args)) (conformance/wrap handler) handler)))]
            (apply test/run-tests (cond-> (vec namespaces) integration? (conj 'pds.output-conformance))))]
      (shutdown-agents)
      (System/exit (if (zero? (+ fail error)) 0 1)))))
