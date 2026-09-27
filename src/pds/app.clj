(ns pds.app
  (:require [clojure.java.io :as io]
            [pds.xrpc :as xrpc]
            [pds.api.server :as server-api]
            [pds.api.admin :as admin-api]
            [pds.api.identity :as identity-api]
            [pds.api.repo :as repo-api]
            [pds.api.blob :as blob-api]
            [pds.api.sync :as sync-api]
            [pds.firehose :as firehose]
            [pds.proxy :as proxy]
            [pds.security.web :as security-web]
            [pds.oauth.client :as oauth-client]
            [pds.oauth.grants :as oauth-grants]
            [pds.oauth.resource :as oauth-resource]
            [pds.oauth.server :as oauth-server]
            [pds.rate-limit :as rate-limit]))

(def version "0.1.0-dev")

(defn handler
  ([config] (handler config nil))
  ([config ds]
   (let [config (if ds (oauth-grants/settings ds config) config)
         banner (slurp (io/resource "pds/banner.txt") :encoding "UTF-8")
         resolver (when ds (oauth-client/resolver config))
         routes (merge (when ds (server-api/routes ds config))
                       (when ds (identity-api/routes ds config))
                       (when ds (admin-api/routes ds config))
                       (when ds (repo-api/routes ds config))
                       (when ds (blob-api/routes ds config))
                       (when ds (sync-api/routes ds config))
                       (when ds (firehose/routes ds config))
                       {"/"
                        {:method :get
                         :handler (fn [_] {:status 200
                                           :headers {"Content-Type" "text/plain; charset=utf-8"}
                                           :body banner})}
                        "/xrpc/_health"
                        {:method :get :handler (fn [_] (xrpc/response 200 {:version version}))}
                        "/xrpc/com.atproto.server.describeServer"
                        {:method :get
                         :handler (fn [_]
                                    (xrpc/response 200
                                      {:did (:service-did config)
                                       :inviteCodeRequired (boolean (:invite-required config))
                                       :blobUploadLimit blob-api/max-size
                                       :availableUserDomains (if (and ds (:signup-enabled config))
                                                               [(str "." (:user-domain config))] [])}))}})
         fallback (if ds (proxy/handler ds config)
                      (fn [_] (xrpc/error-response 404 "MethodNotImplemented" "Endpoint is not implemented")))]
     (-> (xrpc/router routes fallback)
         (oauth-resource/wrap ds config resolver)
         (security-web/wrap ds config)
         (oauth-server/wrap ds config resolver)
         (rate-limit/wrap (or (:rate-limiter config) (rate-limit/memory-limiter)))
         (oauth-server/wrap-headers ds config)))))
