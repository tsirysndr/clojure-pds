(ns dagger-main
  (:require [dagger.core :as dag]
            [dagger.container :as container]
            [dagger.modules :as modules]))

(defn ^{:dagger/return :string} hello
  "Greet someone from Clojure."
  [^{:tag String :dagger/default "world"} name]
  (str "Hello, " name "!"))

(defn ^{:dagger/return [:object "Container"]} container-echo
  "Return a container that echoes a message."
  [^:dagger/context context ^String message]
  (-> (dag/container (:client context))
      (container/from "alpine:3.22")
      (container/with-exec ["echo" message])))

(defn -main [& _]
  (modules/serve! (modules/from-ns 'dagger-main "__OBJECT__")))
