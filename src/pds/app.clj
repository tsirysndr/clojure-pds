(ns pds.app
  (:require [pds.xrpc :as xrpc]))

(def version "0.1.0-dev")

(defn handler [config]
  (xrpc/router
   {"/xrpc/_health"
    {:method :get :handler (fn [_] (xrpc/response 200 {:version version}))}
    "/xrpc/com.atproto.server.describeServer"
    {:method :get
     :handler (fn [_]
                (xrpc/response 200 {:did (:service-did config)
                                    :availableUserDomains []}))}}))
