(ns pds.xrpc-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.app :as app]
            [pds.config :as config]
            [pds.xrpc :as xrpc]))

(deftest health-and-dispatch
  (let [handler (app/handler (config/load-config {}))
        health (handler {:request-method :get :uri "/xrpc/_health"})
        missing (handler {:request-method :get :uri "/xrpc/com.example.unknown"})
        wrong-method (handler {:request-method :post :uri "/xrpc/_health"})]
    (is (= 200 (:status health)))
    (is (= {"version" app/version} (json/read-str (:body health))))
    (is (= "application/json; charset=utf-8" (get-in health [:headers "Content-Type"])))
    (is (= 404 (:status missing)))
    (is (= "MethodNotImplemented" (get (json/read-str (:body missing)) "error")))
    (is (= 405 (:status wrong-method)))
    (is (= "GET, HEAD" (get-in wrong-method [:headers "Allow"])))
    (is (= 404 (:status (handler {:request-method :get :uri "/xrpc/_health/"}))))))

(deftest errors-do-not-leak-internals
  (let [handler (xrpc/router {"/fail" {:method :get
                                       :handler (fn [_] (throw (ex-info "secret" {})))}})
        response (binding [*err* (java.io.StringWriter.)]
                   (handler {:request-method :get :uri "/fail"}))]
    (is (= 500 (:status response)))
    (is (= {"error" "InternalServerError" "message" "An internal server error occurred"}
           (json/read-str (:body response))))))

(deftest database-acquisition-timeouts-are-retryable-and-sanitized
  (let [handler (xrpc/router {"/busy" {:method :get
                                       :handler (fn [_] (throw (java.sql.SQLTransientConnectionException. "secret JDBC settings")))}})
        response (handler {:request-method :get :uri "/busy"})]
    (is (= 503 (:status response)))
    (is (= "1" (get-in response [:headers "Retry-After"])))
    (is (= {"error" "ServiceUnavailable" "message" "Database connections are temporarily unavailable"}
           (json/read-str (:body response))))))
