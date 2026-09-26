(ns pds.api.server
  (:require [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.app-passwords :as app-passwords]
            [pds.auth :as auth]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.invites :as invites]
            [pds.identity :as identity]
            [pds.migration :as migration]
            [pds.request :as request]
            [pds.service-auth :as service-auth]
            [pds.xrpc :as xrpc]))

(defn json-route [method f] {:method method :handler #(xrpc/response 200 (f %))})
(defn empty-route [f] {:method :post :handler (fn [r] (f r) {:status 200 :headers {} :body ""})})
(defn authenticated
  ([ds settings f] (authenticated ds settings {} f))
  ([ds settings options f]
  (fn [request]
    (db/transact! ds (fn [conn] (f conn (auth/authenticate! conn settings request options) request))))))
(defn routes [ds settings]
  (let [resolver (identity/resolver settings)]
  {"/xrpc/com.atproto.server.getServiceAuth"
   {:method :get
    :handler (fn [r]
               (assoc-in (xrpc/response 200 ((authenticated ds settings {:allow-deactivated? true :allow-taken-down? true}
                                             (fn [conn account r] (service-auth/issue! conn settings account (request/query-params r)))) r))
                         [:headers "Cache-Control"] "no-store"))}
   "/xrpc/com.atproto.server.createAccount"
   (json-route :post (fn [r]
                       (let [body (request/json-body r)]
                         (if (contains? body "did")
                           (migration/create! ds settings resolver r body)
                           (accounts/create! ds settings body)))))
   "/xrpc/com.atproto.server.createSession"
   (json-route :post #(accounts/login! ds settings (request/json-body %)))
   "/xrpc/com.atproto.server.refreshSession"
   (json-route :post #(auth/refresh! ds settings %))
   "/xrpc/com.atproto.server.deleteSession"
   (empty-route #(auth/delete-session! ds settings %))
   "/xrpc/com.atproto.server.getSession"
   (json-route :get (authenticated ds settings {:allow-deactivated? true} (fn [_ account _] (accounts/public-account account))))
   "/xrpc/com.atproto.server.getAccountInviteCodes"
   (json-route :get (authenticated ds settings (fn [conn account r] (invites/account-codes conn (:did account) (request/query-params r)))))
   "/xrpc/com.atproto.server.deactivateAccount"
   (empty-route (authenticated ds settings {:allow-deactivated? true} (fn [conn account r] (accounts/deactivate! conn account (request/json-body r)))))
   "/xrpc/com.atproto.server.activateAccount"
   (empty-route (authenticated ds settings {:allow-deactivated? true} (fn [conn account _] (accounts/activate! conn account))))
   "/xrpc/com.atproto.server.requestAccountDelete"
   (empty-route (authenticated ds settings {:allow-deactivated? true} (fn [conn account _] (accounts/request-deletion! conn settings account))))
   "/xrpc/com.atproto.server.deleteAccount"
   (empty-route #(accounts/delete! ds (request/json-body %)))
   "/xrpc/com.atproto.server.createAppPassword"
   (json-route :post (authenticated ds settings (fn [conn account r] (app-passwords/create! conn settings account (request/json-body r)))))
   "/xrpc/com.atproto.server.listAppPasswords"
   (json-route :get (authenticated ds settings (fn [conn account _] (app-passwords/list-passwords conn account))))
   "/xrpc/com.atproto.server.revokeAppPassword"
   (empty-route (authenticated ds settings (fn [conn account r] (app-passwords/revoke! conn account (request/json-body r)))))
   "/xrpc/com.atproto.server.requestEmailConfirmation"
   (empty-route (authenticated ds settings (fn [conn account _] (accounts/request-confirmation! conn settings account))))
   "/xrpc/com.atproto.server.confirmEmail"
   (empty-route (authenticated ds settings (fn [conn account r] (accounts/confirm! conn account (request/json-body r)))))
   "/xrpc/com.atproto.server.requestEmailUpdate"
   (json-route :post (authenticated ds settings (fn [conn account _] (accounts/request-email-update! conn settings account))))
   "/xrpc/com.atproto.server.updateEmail"
   (empty-route (authenticated ds settings (fn [conn account r] (accounts/update-email! conn settings account (request/json-body r)))))
   "/xrpc/com.atproto.server.requestPasswordReset"
   (empty-route #(accounts/request-reset! ds settings (request/json-body %)))
   "/xrpc/com.atproto.server.resetPassword"
   (empty-route #(accounts/reset-password! ds (request/json-body %)))
   "/.well-known/atproto-did"
   {:method :get :handler (fn [r]
                           (with-open [conn (db/connection ds)]
                             (let [host (some-> (get-in r [:headers "host"]) (str/split #":") first str/lower-case)
                                   account (accounts/resolve-identity conn host)]
                               {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8"} :body (:did account)})))}
   "/.well-known/did.json"
   (json-route :get
               (fn [r]
                 (with-open [conn (db/connection ds)]
                   (let [host (some-> (get-in r [:headers "host"]) (str/split #":") first str/lower-case)]
                     (if (= host (:hostname settings))
                       {:id (:service-did settings)
                        :service [{:id "#atproto_pds" :type "AtprotoPersonalDataServer" :serviceEndpoint (:public-url settings)}]}
                       (let [account (accounts/resolve-identity conn (str "did:web:" host))
                             repo (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" (:did account)))]
                         (accounts/did-document conn settings account (:public_key repo))))))))}))
