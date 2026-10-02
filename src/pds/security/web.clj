(ns pds.security.web
  (:require [clojure.data.json :as json]
            [pds.accounts :as accounts]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pds.errors :as errors]
            [pds.request :as request]
            [pds.security.browser :as browser]
            [pds.security.identity :as identity]
            [pds.security.passkeys :as passkeys]))

(def headers
  {"Cache-Control" "no-store" "Pragma" "no-cache" "X-Content-Type-Options" "nosniff"
   "Referrer-Policy" "no-referrer" "X-Frame-Options" "DENY"
   "Content-Security-Policy" "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
   "Permissions-Policy" "publickey-credentials-get=(self), publickey-credentials-create=(self)"})
(defn- secure? [settings] (str/starts-with? (:public-url settings) "https://"))
(defn cookie-name [settings] (if (secure? settings) "__Host-pds-security" "pds-security"))
(defn- cookie [settings token]
  (str (cookie-name settings) "=" (or token "") "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" (if token browser/lifetime 0)
       (when (secure? settings) "; Secure")))
(defn token [settings request]
  (let [values (keep (fn [part] (let [[k v] (str/split (str/trim part) #"=" 2)] (when (= k (cookie-name settings)) v)))
                     (str/split (get-in request [:headers "cookie"] "") #";"))]
    (when (= 1 (count values)) (first values))))
(defn- json-response [status body] {:status status :headers {"Content-Type" "application/json; charset=utf-8"} :body (json/write-str body)})
(defn- response [settings result]
  (if (:logout result)
    (assoc-in (json-response 200 {:stage "login"}) [:headers "Set-Cookie"] (cookie settings nil))
    (let [data (merge (:view result) {:result (:result result)}) error (get-in result [:result :error])
          output (json-response (if error (get-in result [:result :status] 400) 200) data)]
      (assoc-in output [:headers "Set-Cookie"] (cookie settings (:token result))))))
(defn body! [settings request]
  (when-not (and (= (:public-url settings) (get-in request [:headers "origin"]))
                 (or (nil? (get-in request [:headers "sec-fetch-site"])) (= "same-origin" (get-in request [:headers "sec-fetch-site"]))))
    (errors/raise! 403 "InvalidOrigin" "Open this page directly on your PDS to continue"))
  (when-not (and (= "application/json" (some-> (get-in request [:headers "content-type"]) (str/split #";" 2) first str/trim str/lower-case))
                 (or (nil? (get-in request [:headers "content-encoding"])) (= "identity" (get-in request [:headers "content-encoding"]))))
    (errors/invalid! "Expected an unencoded JSON body"))
  (let [data (request/body-bytes request 71680)
        value (try (request/json-value data) (catch Exception _ (errors/invalid! "Invalid JSON object")))]
    (when-not (map? value) (errors/invalid! "Expected a JSON object")) value))
(defn handler [ds settings]
  (let [assets {"/account" ["text/html; charset=utf-8" "security/index.html"]
                "/account/" ["text/html; charset=utf-8" "security/index.html"]
                "/account/app.js" ["text/javascript; charset=utf-8" "security/app.js"]
                "/account/style.css" ["text/css; charset=utf-8" "security/style.css"]}
        assets (into {} (map (fn [[path [mime file]]] [path [mime (slurp (io/resource file))]]) assets))
        passkeys? (try (passkeys/rp-origin settings) true (catch Exception _ false))]
    (fn [request]
      (let [output
            (try
              (when (seq (:query-string request)) (errors/invalid! "Query parameters are not supported"))
              (cond
                ;; The reset link from the email lands here with the token in
                ;; the path; the page is the same bundle, which reads it back
                ;; out of location.pathname.
                (and (#{:get :head} (:request-method request))
                     (re-matches #"/account/(?:reset|confirm)(?:/[A-Za-z0-9_-]{1,256})?" (:uri request)))
                (let [[mime content] (get assets "/account")]
                  {:status 200 :headers {"Content-Type" mime "Cache-Control" "no-cache"} :body content})

                (and (#{:get :head} (:request-method request)) (contains? assets (:uri request)))
                ;; The bundle is served under a fixed name, so a CDN in front
                ;; must revalidate on every fetch or a deploy leaves stale
                ;; JavaScript at the edge until its default TTL runs out.
                (let [[mime content] (get assets (:uri request))]
                  {:status 200 :headers {"Content-Type" mime "Cache-Control" "no-cache"} :body content})
                ;; The emailed confirmation link lands signed out; the token it
                ;; carries is the whole proof.
                (and (= :post (:request-method request)) (= "/account/confirm" (:uri request)))
                (do (accounts/confirm-by-token! ds (body! settings request))
                    (json-response 200 {"confirmed" true}))

                (and (= :get (:request-method request)) (= "/account/session" (:uri request)))
                (let [result (browser/open! ds (token settings request))]
                  (response settings (update result :view assoc :passkeys-available passkeys? :origin (:public-url settings)
                                                :signup-enabled (boolean (:signup-enabled settings))
                                                :email-enabled (boolean (:email-enabled settings))
                                                :invite-required (boolean (:invite-required settings)) :user-domain (:user-domain settings))))
                (and (= :post (:request-method request)) (str/starts-with? (:uri request) "/account/action/"))
                (let [body (body! settings request)
                      action (subs (:uri request) (count "/account/action/"))
                      result (case action
                               "identity/recovery/change"
                               (identity/change! ds settings (token settings request) (get-in request [:headers "x-csrf-token"]) body)
                               "signup"
                               (browser/register! ds settings (token settings request) (get-in request [:headers "x-csrf-token"]) body)
                               (browser/action! ds settings (token settings request) (get-in request [:headers "x-csrf-token"]) action body))]
                  (response settings result))
                :else (json-response 404 {:error "NotFound" :message "Page was not found"}))
              (catch clojure.lang.ExceptionInfo e
                (if (:xrpc (ex-data e))
                  (json-response (:status (ex-data e)) {:error (:error (ex-data e)) :message (.getMessage e)})
                  (json-response 500 {:error "ServerError" :message "Something went wrong. Try again."})))
              (catch Exception _ (json-response 500 {:error "ServerError" :message "Something went wrong. Try again."})))]
        (update output :headers merge headers)))))
(defn wrap [next-handler ds settings]
  (let [web (when ds (handler ds settings))]
    (fn [request]
      (if (and web (or (= "/account" (:uri request)) (str/starts-with? (:uri request) "/account/")))
        (web request) (next-handler request)))))
