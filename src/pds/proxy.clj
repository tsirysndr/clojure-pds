(ns pds.proxy
  (:require [clojure.string :as str]
            [pds.auth :as auth]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.identity :as identity]
            [pds.net :as net]
            [pds.oauth.permissions :as permissions]
            [pds.oauth.resource :as oauth-resource]
            [pds.protocol.syntax :as syntax]
            [pds.request :as request]
            [pds.service-auth :as service-auth]
            [pds.xrpc :as xrpc])
  (:import [java.util.concurrent Semaphore]))

(defn service! [value]
  (let [[did fragment :as parts] (when (string? value) (str/split value #"#" -1))]
    (when-not (and (= 2 (count parts)) (service-auth/audience? value) (identity/supported-did? did))
      (errors/invalid! "Proxy service must be a supported DID with a service fragment"))
    {:did did :fragment fragment :audience value}))

(defn settings [env]
  (merge
    (into {} (for [[key variable] [[:proxy-appview-service "PDS_APPVIEW_SERVICE"] [:proxy-labeler-service "PDS_LABELER_SERVICE"]]
                   :let [value (get env variable)] :when value]
               (do (try (service! value) (catch Exception _ (throw (ex-info (str variable " must be a DID service reference") {}))))
                   [key value])))
    (into {} (for [[key variable default maximum] [[:proxy-max-concurrent "PDS_PROXY_MAX_CONCURRENT" 16 256]
                                                  [:proxy-max-request-bytes "PDS_PROXY_MAX_REQUEST_BYTES" 5242880 67108864]
                                                  [:proxy-max-response-bytes "PDS_PROXY_MAX_RESPONSE_BYTES" 10485760 67108864]
                                                  [:proxy-timeout-ms "PDS_PROXY_TIMEOUT_MS" 10000 60000]]
                   :let [value (get env variable (str default))]]
               (do (when-not (and (string? value) (re-matches #"[0-9]{1,8}" value) (<= 1 (Long/parseLong value) maximum))
                     (throw (ex-info (str variable " is outside the allowed range") {})))
                   [key (Long/parseLong value)])))))

(defn destination! [resolver {:keys [did fragment]}]
  (let [document (identity/bounded-call! resolver #(identity/resolve-did! resolver did))
        services (get document "service")
        matches (when (vector? services)
                  (filter #(and (map? %) (#{(str "#" fragment) (str did "#" fragment)} (get % "id"))) services))
        service (first matches)]
    (when-not (and (= 1 (count matches)) (string? (get service "type")) (seq (get service "type")))
      (errors/invalid! "DID document has no unique matching proxy service"))
    (try (identity/origin! (get service "serviceEndpoint"))
         (catch Exception _ (errors/invalid! "Proxy service endpoint must be an HTTPS origin")))))

(defn request-headers [request]
  (let [headers (:headers request)
        nominated (set (map str/trim (str/split (str/lower-case (get headers "connection" "")) #",")))]
    (into {} (filter (fn [[name _]]
                       (and (not (nominated name))
                            (or (#{"accept" "accept-language" "content-type" "atproto-accept-labelers" "x-bsky-topics"} name)
                                (str/starts-with? name "x-atproto-"))))) headers)))

(defn response! [method {:keys [status headers body]}]
  (let [nominated (map str/trim (str/split (str/lower-case (get headers "connection" "")) #","))
        headers (apply dissoc headers nominated)
        forwarded (into {} (for [name ["content-language" "atproto-repo-rev" "atproto-content-labelers" "retry-after"]
                                :let [value (get headers name)] :when value] [name value]))
        base {"Cache-Control" "no-store" "X-Content-Type-Options" "nosniff"
              "Content-Security-Policy" "default-src 'none'; sandbox"}]
    (cond
      (<= 200 status 299)
      {:status status :body body
       :headers (merge base forwarded {"Content-Type" (get headers "content-type" "application/octet-stream")}
                       (when (and (= method :head) (re-matches #"[0-9]{1,18}" (get headers "content-length" "")))
                         {"Content-Length" (get headers "content-length")}))}
      (<= 400 status 599)
      (let [payload (try (request/json-value body) (catch Exception _ nil))
            code (get payload "error") message (get payload "message")
            valid? (and (string? code) (re-matches #"[A-Za-z][A-Za-z0-9_]{0,127}" code))]
        (update (xrpc/error-response status (if valid? code "UpstreamFailure")
                                    (if (and valid? (string? message) (<= (count message) 4096)) message "Upstream service returned an error"))
                :headers merge base forwarded (when (= status 401) {"WWW-Authenticate" "Bearer"})))
      :else (errors/raise! 502 "UpstreamFailure" "Upstream redirects and protocol upgrades are not supported"))))

(defn handler [ds supplied-settings]
  (let [config (merge (settings {}) supplied-settings)
        resolver (identity/resolver config) permits (Semaphore. (int (:proxy-max-concurrent config)))]
    (fn [r]
      (let [r (assoc r ::permissions/proxy true)
            uri (:uri r) method (when (str/starts-with? uri "/xrpc/") (subs uri 6))
            target (or (get-in r [:headers "atproto-proxy"])
                       (if (or (= method "com.atproto.moderation.createReport") (some-> method (str/starts-with? "tools.ozone.")))
                         (:proxy-labeler-service config) (:proxy-appview-service config)))]
        (if-not (and target (syntax/nsid? method))
          (xrpc/error-response 404 "MethodNotImplemented" "Endpoint is not implemented")
          (do
            (when-not (#{:get :head :post} (:request-method r))
              (throw (ex-info "Proxy supports GET, HEAD and POST" {:xrpc true :status 405 :error "MethodNotAllowed" :allow "GET, HEAD, POST"})))
            ;; Clients may offer h2c while sending an ordinary HTTP/1.1 request.
            ;; Ignore transport upgrade offers; never forward hop-by-hop headers.
            (when (or (some #{"websocket"} (map str/trim (str/split (str/lower-case (get-in r [:headers "upgrade"] "")) #",")))
                      (and (get-in r [:headers "dpop"]) (not (oauth-resource/dpop? r))))
              (errors/invalid! "WebSocket upgrades and DPoP passthrough are not supported"))
            (when-not (.tryAcquire permits) (errors/raise! 503 "ProxyBusy" "Proxy concurrency limit reached"))
            (try
              ;; Authenticate before any remote lookup or reading a request body.
              (db/transact! ds #(service-auth/authorize! (auth/authenticate! % config r) method))
              (let [service (service! target)
                    body (when (= :post (:request-method r)) (request/body-bytes r (:proxy-max-request-bytes config)))
                    _ (when (and body (get-in r [:headers "content-encoding"])
                                 (not= "identity" (str/lower-case (get-in r [:headers "content-encoding"]))))
                        (errors/invalid! "Encoded proxy request bodies are not supported"))
                    origin (destination! resolver service)
                    query (:query-string r)
                    url (str origin uri (when (seq query) (str "?" query)))
                    ;; Recheck account/session after remote resolution. No DB
                    ;; transaction spans DID fetches or the upstream request.
                    token (db/transact! ds (fn [conn]
                                            (:token (service-auth/issue! conn config (auth/authenticate! conn config r)
                                                                        {"aud" target "lxm" method}))))
                    headers (cond-> (assoc (request-headers r) "authorization" (str "Bearer " token))
                              body (update "content-type" #(or % "application/octet-stream")))
                    upstream (try
                               (when-not (:http-client config) (throw (ex-info "Proxy client unavailable" {})))
                               (net/exchange! (:http-client config) url
                                              {:method (str/upper-case (name (:request-method r))) :headers headers :body body
                                               :maximum (:proxy-max-response-bytes config) :timeout-ms (:proxy-timeout-ms config)})
                               (catch Exception _ (errors/raise! 502 "UpstreamFailure" "Upstream service request failed")))]
                (response! (:request-method r) upstream))
              (finally (.release permits)))))))))
