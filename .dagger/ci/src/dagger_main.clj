(ns dagger-main
  (:require [ci.pipeline :as pipeline]
            [ci.runtime :as runtime]))

(defn -main [& _]
  (runtime/serve! (pipeline/definition)))
