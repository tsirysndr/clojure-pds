(ns pds.api.preferences
  (:require [pds.api.server :as server]
            [pds.oauth.permissions :as permissions]
            [pds.preferences :as preferences]
            [pds.request :as request]))

(defn audience [settings]
  (or (:proxy-appview-service settings) (str (:service-did settings) "#atproto_pds")))
(defn routes [ds settings proxy-handler]
  (into {}
    (for [[method name] [[:get "getPreferences"] [:post "putPreferences"]]
          :let [id (str "app.bsky.actor." name)
                local (server/authenticated ds settings {:allow-deactivated? true :allow-taken-down? (= method :get)}
                        (fn [conn account r]
                          (when (:oauth-scope account) (permissions/rpc! account id (audience settings)))
                          (if (= method :get)
                            (do (request/query-params r) (preferences/read! conn account))
                            (preferences/put! conn account (get (request/json-body r) "preferences")))))
                handler (:handler (if (= method :get) (server/json-route :get local) (server/empty-route local)))]]
      [(str "/xrpc/" id)
       {:method method :handler
        (fn [r]
          (if-let [target (get-in r [:headers "atproto-proxy"])]
            (if (= target (audience settings))
              (assoc-in (handler r) [:headers "Cache-Control"] "no-store")
              (proxy-handler r))
            (assoc-in (handler r) [:headers "Cache-Control"] "no-store")))}])))
