(ns pds.http-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [pds.app :as app]
            [pds.config :as config]
            [pds.http :as http])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn request [client port method path]
  (.send ^HttpClient client
         (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
             (.timeout (Duration/ofSeconds 5))
             (.method method (HttpRequest$BodyPublishers/noBody))
             .build)
         (HttpResponse$BodyHandlers/ofString)))

(deftest real-http-lifecycle
  (let [settings (config/load-config {"PDS_PORT" "0"})
        handler (app/handler settings)
        {:keys [port stop!]} (http/start! settings handler)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [health (request client port "GET" "/xrpc/_health?ignored=true")
              head (request client port "HEAD" "/xrpc/_health")
              wrong-method (request client port "POST" "/xrpc/_health")
              missing (request client port "GET" "/not-found")
              discovery (request client port "GET" "/xrpc/com.atproto.server.describeServer")
              root (request client port "GET" "/")
              root-head (request client port "HEAD" "/")]
          (is (= 200 (.statusCode root)))
          (is (= "text/plain; charset=utf-8"
                 (.orElse (.firstValue (.headers root) "content-type") nil)))
          (is (= (slurp (io/resource "pds/banner.txt") :encoding "UTF-8")
                 (.body root)))
          (is (= 200 (.statusCode root-head)))
          (is (= "" (.body root-head)))
          (is (pos? port))
          (is (= 200 (.statusCode health)))
          (is (= {"version" app/version} (json/read-str (.body health))))
          (is (= "application/json; charset=utf-8"
                 (.orElse (.firstValue (.headers health) "content-type") nil)))
          (is (= 200 (.statusCode head)))
          (is (= "" (.body head)))
          (is (= (str (count (.body health)))
                 (.orElse (.firstValue (.headers head) "content-length") nil)))
          (is (= 405 (.statusCode wrong-method)))
          (is (= 404 (.statusCode missing)))
          (is (= "MethodNotImplemented" (get (json/read-str (.body missing)) "error")))
          (is (= 200 (.statusCode discovery)))
          (is (= {"did" "did:web:localhost" "availableUserDomains" []}
                 (json/read-str (.body discovery))))))
      (finally (stop!)))
    (stop!)
    ;; Closing releases the listening port, and a fresh server can reuse it.
    (let [restarted (http/start! (assoc settings :port port) handler)]
      (try (is (= port (:port restarted)))
           (finally ((:stop! restarted)))))))
