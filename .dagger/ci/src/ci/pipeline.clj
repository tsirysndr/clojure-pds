(ns ci.pipeline
  "Collect the exported functions from the individual checks."
  (:require [ci.frontend]
            [ci.integration]
            [ci.sqlite]
            [ci.nix]
            [dagger.modules :as modules]))

(defn definition []
  (reduce (fn [module namespace]
            (update module :functions merge
                    (:functions (modules/from-ns namespace "ProjectCi"))))
          {:name "ProjectCi" :functions {}}
          '[ci.frontend ci.integration ci.sqlite ci.nix]))
