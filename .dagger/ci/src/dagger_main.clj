(ns dagger-main
  (:require [ci.pipeline :as pipeline]
            [dagger.core :as dag]
            [dagger.modules :as modules]))

(defn -main [& _]
  (with-open [client (dag/connect {:timeout-ms 3600000})]
    (modules/serve-with-client! client (pipeline/definition))))
