(ns dagger.core
  "Public entry point. Use with-open for sessions and -> for container pipelines."
  (:refer-clojure :exclude [load])
  (:require [dagger.connection :as connection]
            [dagger.internal.runtime :as runtime]))

(def connect connection/connect)
(def with-transport connection/with-transport)
(def query! connection/request!)
(def object? runtime/object?)
(def id runtime/object-id)

(defn load
  "Load an engine object by type name and ID without immediately executing."
  [client type-name id]
  (when-not (and (string? id) (#{:object :interface} (get-in @runtime/schema [:types type-name :kind])))
    (throw (ex-info "Expected a schema object type and ID string" {:type :dagger/invalid-argument :object type-name})))
  (runtime/load-object client type-name id))

(defn container
  ([client] (container client {}))
  ([client options] (runtime/invoke-field client (:query-type @runtime/schema) "container" options)))
(defn directory [client] (runtime/invoke-field client (:query-type @runtime/schema) "directory" {}))
(defn host [client] (runtime/invoke-field client (:query-type @runtime/schema) "host" {}))
(defn cache-volume [client key] (runtime/invoke-field client (:query-type @runtime/schema) "cacheVolume" {:key key}))
