(ns pds.app
  (:require [clojure.java.io :as io]
            [pds.xrpc :as xrpc]))

(def version "0.1.0-dev")

(defn handler [config]
  (let [banner (slurp (io/resource "pds/banner.txt") :encoding "UTF-8")]
    (xrpc/router
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
                  (xrpc/response 200 {:did (:service-did config)
                                      :availableUserDomains []}))}})))
