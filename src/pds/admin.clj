(ns pds.admin
  (:require [pds.errors :as errors])
  (:import [java.security MessageDigest]
           [java.util Base64]))

(defn settings [env]
  (let [password (get env "PDS_ADMIN_PASSWORD")]
    (when (and password (not (<= 16 (count password) 1024)))
      (throw (ex-info "PDS_ADMIN_PASSWORD must contain 16 to 1024 characters" {})))
    {:admin-password password}))

(defn authenticate! [settings request]
  (when-not (:admin-password settings)
    (errors/raise! 403 "Forbidden" "Administrative access is disabled"))
  (let [header (get-in request [:headers "authorization"])
        encoded (when (and (string? header) (< (count header) 8192))
                  (second (re-matches #"(?i)Basic ([A-Za-z0-9+/]+={0,2})" header)))
        supplied (try (when encoded (.decode (Base64/getDecoder) ^String encoded))
                      (catch IllegalArgumentException _ nil))
        expected (.getBytes (str "admin:" (:admin-password settings)) "UTF-8")
        digest #(.digest (MessageDigest/getInstance "SHA-256") ^bytes %)]
    (when-not (and supplied (MessageDigest/isEqual (digest expected) (digest supplied)))
      (throw (ex-info "Administrative credentials are required"
                      {:xrpc true :status 401 :error "AuthenticationRequired"
                       :www-authenticate "Basic realm=\"PDS admin\", charset=\"UTF-8\""}))))
  true)
