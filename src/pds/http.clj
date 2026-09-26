(ns pds.http
  (:require [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors ExecutorService]))

(defn- request-map [^HttpExchange exchange]
  {:remote-addr (.getHostAddress (.getAddress (.getRemoteAddress exchange)))
   :request-method (-> (.getRequestMethod exchange) str/lower-case keyword)
   :uri (.getRawPath (.getRequestURI exchange))
   :query-string (.getRawQuery (.getRequestURI exchange))
   :headers (into {} (map (fn [[k v]] [(str/lower-case k) (str/join "," v)]))
                  (.getRequestHeaders exchange))
   :body (.getRequestBody exchange)})

(defn- respond! [^HttpExchange exchange {:keys [status headers body]}]
  (let [bytes (if (bytes? body) body (.getBytes ^String (or body "") StandardCharsets/UTF_8))
        no-body? (or (= "HEAD" (.getRequestMethod exchange))
                     (#{204 304} status))]
    (doseq [[k v] headers]
      (.set (.getResponseHeaders exchange) k v))
    (when (= "HEAD" (.getRequestMethod exchange))
      (.set (.getResponseHeaders exchange) "Content-Length" (str (alength bytes))))
    (.sendResponseHeaders exchange status (if no-body? -1 (alength bytes)))
    (when-not no-body?
      (.write (.getResponseBody exchange) bytes))))

(defn start!
  "Start an HTTP server; returns its bound port and an idempotent stop! function.
  The supplied handler uses Ring-shaped maps. Bodies are UTF-8 strings for now."
  [{:keys [host port]} handler]
  (let [server (HttpServer/create)
        executor (Executors/newVirtualThreadPerTaskExecutor)]
    (try
      (.bind server (InetSocketAddress. ^String host (int port)) 128)
      (.setExecutor server executor)
      (.createContext server "/"
                      (reify HttpHandler
                        (handle [_ exchange]
                          (try
                            (respond! exchange (handler (request-map exchange)))
                            (finally (.close ^HttpExchange exchange))))))
      (.start server)
      (let [stopped? (atom false)]
        {:port (.getPort (.getAddress server))
         :stop! (fn []
                  (when (compare-and-set! stopped? false true)
                    (try (.stop server 1)
                         (finally (.shutdownNow ^ExecutorService executor)))))})
      (catch Exception e
        (.stop server 0)
        (.shutdownNow ^ExecutorService executor)
        (throw e)))))
