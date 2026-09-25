(ns pds.xrpc
  (:require [clojure.data.json :as json]))

(defn response [status body]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (json/write-str body)})

(defn error-response [status error message]
  (response status {:error error :message message}))

(defn router
  "Dispatch explicit routes. Handlers accept a request map and return a response.
  HEAD has GET semantics; the transport is responsible for suppressing its body."
  [routes]
  (fn [{:keys [uri request-method] :as request}]
    (try
      (if-let [{:keys [method handler]} (get routes uri)]
        (if (or (= method request-method)
                (and (= method :get) (= request-method :head)))
          (handler request)
          (assoc-in (error-response 405 "MethodNotAllowed" "HTTP method is not supported")
                    [:headers "Allow"] (if (= method :get) "GET, HEAD" "POST")))
        (error-response 404 "MethodNotImplemented" "Endpoint is not implemented"))
      (catch Exception _
        ;; Do not expose exception messages (which may contain credentials).
        (binding [*out* *err*] (println "XRPC handler failed"))
        (error-response 500 "InternalServerError" "An internal server error occurred")))))
