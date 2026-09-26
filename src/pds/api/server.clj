(ns pds.api.server
  (:require [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.request :as request]
            [pds.xrpc :as xrpc]))

(defn json-route [method f] {:method method :handler #(xrpc/response 200 (f %))})
(defn empty-route [f] {:method :post :handler (fn [r] (f r) {:status 200 :headers {} :body ""})})
(defn authenticated [ds settings f]
  (fn [request]
    (db/transact! ds (fn [conn] (f conn (auth/authenticate! conn settings request) request)))))
(defn routes [ds settings]
  {"/xrpc/com.atproto.server.createAccount"
   (json-route :post #(accounts/create! ds settings (request/json-body %)))
   "/xrpc/com.atproto.server.createSession"
   (json-route :post #(accounts/login! ds settings (request/json-body %)))
   "/xrpc/com.atproto.server.refreshSession"
   (json-route :post #(auth/refresh! ds settings %))
   "/xrpc/com.atproto.server.deleteSession"
   (empty-route #(auth/delete-session! ds settings %))
   "/xrpc/com.atproto.server.getSession"
   (json-route :get (authenticated ds settings (fn [_ account _] (accounts/public-account account))))
   "/xrpc/com.atproto.server.requestEmailConfirmation"
   (empty-route (authenticated ds settings (fn [conn account _] (accounts/request-confirmation! conn settings account))))
   "/xrpc/com.atproto.server.confirmEmail"
   (empty-route (authenticated ds settings (fn [conn account r] (accounts/confirm! conn account (request/json-body r)))))
   "/xrpc/com.atproto.server.requestPasswordReset"
   (empty-route #(accounts/request-reset! ds settings (request/json-body %)))
   "/xrpc/com.atproto.server.resetPassword"
   (empty-route #(accounts/reset-password! ds (request/json-body %)))
   "/xrpc/com.atproto.identity.resolveHandle"
   (json-route :get (fn [r]
                      (with-open [conn (db/connection ds)]
                        (let [handle (get (request/query-params r) "handle")]
                          {:did (:did (accounts/resolve-account conn handle))}))))
   "/.well-known/atproto-did"
   {:method :get :handler (fn [r]
                           (with-open [conn (db/connection ds)]
                             (let [host (some-> (get-in r [:headers "host"]) (str/split #":") first str/lower-case)
                                   account (accounts/resolve-account conn host)]
                               {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8"} :body (:did account)})))}
   "/.well-known/did.json"
   (json-route :get
               (fn [r]
                 (with-open [conn (db/connection ds)]
                   (let [host (some-> (get-in r [:headers "host"]) (str/split #":") first str/lower-case)]
                     (if (= host (:hostname settings))
                       {:id (:service-did settings)
                        :service [{:id "#atproto_pds" :type "AtprotoPersonalDataServer" :serviceEndpoint (:public-url settings)}]}
                       (let [account (accounts/resolve-account conn host)
                             repo (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" (:did account)))]
                         (accounts/did-document settings account (:public_key repo))))))))})
