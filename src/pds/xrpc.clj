(ns pds.xrpc
  (:require [clojure.data.json :as json]
            [pds.endpoint :as endpoint]))

(defn response [status body]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (json/write-str body)})

(defn error-response [status error message]
  (response status {:error error :message message}))

(defn router
  "Dispatch explicit routes. Handlers accept a request map and return a response.
  HEAD has GET semantics; the transport is responsible for suppressing its body."
  ([routes] (router routes (fn [_] (error-response 404 "MethodNotImplemented" "Endpoint is not implemented"))))
  ([routes fallback]
  (let [routes (into {} (map (fn [[uri route]]
                              [uri (assoc route ::endpoint/context (endpoint/context uri (:method route)))])) routes)]
    (fn [{:keys [uri request-method] :as request}]
      (try
        (if-let [{:keys [method handler] :as route} (get routes uri)]
          (if (or (= method request-method)
                  (and (= method :get) (= request-method :head)))
            (handler (assoc request ::endpoint/context (::endpoint/context route)))
            (assoc-in (error-response 405 "MethodNotAllowed" "HTTP method is not supported")
                      [:headers "Allow"] (if (= method :get) "GET, HEAD" "POST")))
          (fallback request))
        (catch clojure.lang.ExceptionInfo e
          (if (:xrpc (ex-data e))
            (let [{:keys [status error www-authenticate allow retry-after]} (ex-data e)]
              (cond-> (error-response status error (.getMessage e))
                allow (assoc-in [:headers "Allow"] allow)
                retry-after (assoc-in [:headers "Retry-After"] retry-after)
                (= status 401) (assoc-in [:headers "WWW-Authenticate"] (or www-authenticate "Bearer"))))
            (error-response 500 "InternalServerError" "An internal server error occurred")))
        (catch java.sql.SQLTransientConnectionException _
          (assoc-in (error-response 503 "ServiceUnavailable" "Database connections are temporarily unavailable")
                    [:headers "Retry-After"] "1"))
        (catch Exception _
          ;; Do not expose exception messages (which may contain credentials).
          (binding [*out* *err*] (println "XRPC handler failed"))
          (error-response 500 "InternalServerError" "An internal server error occurred")))))))
