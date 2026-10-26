(ns ci.common
  "Shared lazy containers and source filtering."
  (:require [dagger.core :as dag]
            [dagger.container :as ctr]
            [dagger.directory :as dir]))

(defn image [client address] (ctr/from (dag/container client) address))
(defn commands [container argv] (reduce ctr/with-exec container argv))
(defn clean [source]
  (reduce dir/without-directory source
          [".git" ".dagger" ".cpcache" ".gitlibs" "target" "node_modules"
           "frontend/node_modules" "scripts/conformance/node_modules"]))
(defn finish [container] (ctr/sync container) "Checks passed")
(defn node [client]
  (-> (image client "node:24-bookworm-slim")
      (ctr/with-file "/usr/local/bin/bun"
                     (ctr/file (image client "oven/bun:1.4.2") "/usr/local/bin/bun"))))

(defn runner [client source]
  ;; The existing builder pins JDK 25 and Clojure CLI 1.12.4.1618.
  (-> (dir/docker-build (clean source) {:target "builder"})
      (ctr/with-directory "/src" (clean source))
      (ctr/with-directory "/usr/local" (ctr/directory (node client) "/usr/local"))
      (ctr/with-mounted-cache "/root/.m2" (dag/cache-volume client "clojure-pds-maven"))
      (ctr/with-workdir "/src")
      (ctr/with-env-variable "GITLIBS" "/root/.gitlibs")
      (ctr/with-env-variable "CLJ_CACHE" "/src/.cpcache")))
