(ns pds.config
  (:require [clojure.string :as str]))

(defn- invalid! [variable expectation]
  (throw (ex-info (str variable " " expectation) {:variable variable})))

(defn- port [value]
  (when-not (and (string? value) (re-matches #"[0-9]{1,5}" value))
    (invalid! "PDS_PORT" "must be an integer from 0 to 65535"))
  (let [n (Long/parseLong value)]
    (when-not (<= 0 n 65535)
      (invalid! "PDS_PORT" "must be an integer from 0 to 65535"))
    n))

(defn- hostname [value]
  (when-not (and (string? value)
                 (<= 1 (count value) 253)
                 (every? #(and (<= 1 (count %) 63)
                               (re-matches #"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?" %))
                         (str/split value #"\." -1)))
    (invalid! "PDS_HOSTNAME" "must be a lowercase DNS hostname without a scheme, port or path"))
  value)

(defn load-config
  "Validate an environment map. Port zero requests an ephemeral port for local use."
  ([] (load-config (System/getenv)))
  ([env]
   (let [host (get env "PDS_HOST" "127.0.0.1")
         hostname (hostname (get env "PDS_HOSTNAME" "localhost"))]
     (when (or (not (string? host)) (str/blank? host) (re-find #"\s" host))
       (invalid! "PDS_HOST" "must be a nonblank bind address without whitespace"))
     {:host host
      :port (port (get env "PDS_PORT" "3000"))
      :hostname hostname
      :service-did (str "did:web:" hostname)})))
