(ns pds.oauth.web
  "Browser authorization adapter. Client display fields are not trusted branding.
  Cookies carry secrets; URLs contain only random non-secret interaction IDs."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pds.oauth.http :as oauth-http]
            [pds.oauth.interaction :as interaction]
            [pds.oauth.parameters :as parameters]
            [pds.security.web :as security]))

(defn- secure? [settings] (str/starts-with? (:public-url settings) "https://"))
(defn cookie-name [settings] (if (secure? settings) "__Host-pds-oauth" "pds-oauth"))
(defn- cookie [settings value]
  (str (cookie-name settings) "=" value "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" interaction/lifetime
       (when (secure? settings) "; Secure")))
(defn- browser [settings request]
  (let [values (keep (fn [part] (let [[key value] (str/split (str/trim part) #"=" 2)]
                                 (when (= key (cookie-name settings)) value)))
                     (str/split (get-in request [:headers "cookie"] "") #";"))]
    (when (= 1 (count values)) (first values))))
(defn- json-response [status value] (oauth-http/response status value))
(defn handler [ds settings resolver]
  (let [html (slurp (io/resource "security/index.html"))
        error-html (slurp (io/resource "security/oauth-error.html"))]
    (fn [request]
      (let [response
            (try
              (if (= "/oauth/authorize" (:uri request))
                (do
                  (when-not (= :get (:request-method request)) (oauth-http/fail! "invalid_request" "GET is required"))
                  (let [params (parameters/parse! (:query-string request))]
                    (when-not (and (= #{"client_id" "request_uri"} (set (keys params)))
                                   (or (nil? (get-in request [:headers "sec-fetch-mode"])) (= "navigate" (get-in request [:headers "sec-fetch-mode"])))
                                   (or (nil? (get-in request [:headers "sec-fetch-dest"])) (= "document" (get-in request [:headers "sec-fetch-dest"]))))
                      (oauth-http/fail! "invalid_request" "Use a pushed authorization request"))
                    (let [{:keys [id browser]} (interaction/start! ds (get params "client_id") (get params "request_uri"))]
                      {:status 303 :headers {"Location" (str "/oauth/flow/" id) "Set-Cookie" (cookie settings browser)} :body ""})))
                (if-let [[_ id action] (re-matches #"/oauth/flow/([A-Za-z0-9_-]{43})(?:/(state|attach|decide))?" (:uri request))]
                  (let [secret (browser settings request)]
                    (when (seq (:query-string request)) (oauth-http/fail! "invalid_request" "Unexpected query parameters"))
                    (case [(:request-method request) action]
                      [:get nil] (do (interaction/inspect! ds id secret)
                                     {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body html})
                      [:get "state"] (json-response 200 (interaction/inspect! ds id secret))
                      [:post "attach"] (let [body (security/body! settings request)]
                                          (json-response 200 (interaction/authenticate-browser! ds id secret (get-in request [:headers "x-csrf-token"])
                                                                                               (security/token settings request) (get body "accountCsrf"))))
                      [:post "decide"] (let [body (security/body! settings request)]
                                          (json-response 200 (interaction/decide! ds resolver settings id secret (get-in request [:headers "x-csrf-token"])
                                                                               (get body "approve"))))
                      (json-response 405 {:error "invalid_request" :message "Method is not allowed"})))
                  (json-response 404 {:error "NotFound" :message "Page was not found"})))
              (catch clojure.lang.ExceptionInfo e
                (let [data (ex-data e) code (:oauth-error data)]
                  (cond
                    (:xrpc data) (json-response (:status data) {:error (:error data) :message (.getMessage e)})
                    code (json-response 400 {:error code :message (get {"login_required" "Sign out and sign in again to continue."
                                                                                     "access_denied" "Sign in with the account requested by this application."}
                                                                                    code (get oauth-http/descriptions code "Authorization could not be completed. Restart from your application."))})
                    :else (json-response 500 {:error "ServerError" :message "Authorization could not be completed."}))))
              (catch Exception _ (json-response 500 {:error "ServerError" :message "Authorization could not be completed."})))]
        (let [page? (and (= :get (:request-method request))
                         (or (= "/oauth/authorize" (:uri request))
                             (re-matches #"/oauth/flow/[A-Za-z0-9_-]{43}" (:uri request))))
              response (if (and page? (>= (:status response) 400))
                         (-> response (assoc :body error-html) (assoc-in [:headers "Content-Type"] "text/html; charset=utf-8"))
                         response)]
          (update response :headers merge security/headers))))))
