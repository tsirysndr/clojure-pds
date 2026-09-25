(ns pds.test-runner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn -main [& _]
  (let [namespaces (->> (file-seq (io/file "test"))
                        (filter #(.isFile %))
                        (map #(.getPath %))
                        (filter #(str/ends-with? % "_test.clj"))
                        (map #(-> %
                                  (str/replace #"^test/|\.clj$" "")
                                  (str/replace "/" ".")
                                  (str/replace "_" "-")
                                  symbol))
                        sort)]
    (doseq [n namespaces] (require n))
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (shutdown-agents)
      (System/exit (if (zero? (+ fail error)) 0 1)))))
