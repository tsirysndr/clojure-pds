(ns pds.http
  (:require [clojure.string :as str])
  (:import [java.nio ByteBuffer]
           [java.time Duration]
           [java.util.concurrent CompletableFuture Executors ExecutorService Future TimeUnit]
           [org.eclipse.jetty.server Server ServerConnector Request Response Handler$Abstract]
           [org.eclipse.jetty.server.handler ContextHandler]
           [org.eclipse.jetty.util Callback]
           [org.eclipse.jetty.util.thread QueuedThreadPool]
           [org.eclipse.jetty.websocket.server ServerWebSocketContainer WebSocketCreator]
           [org.eclipse.jetty.websocket.api Session Session$Listener$AutoDemanding]))

(defn- request-map [^Request request]
  {:remote-addr (Request/getRemoteAddr request)
   :request-method (-> (.getMethod request) str/lower-case keyword)
   :uri (.getPath (.getHttpURI request))
   :query-string (.getQuery (.getHttpURI request))
   :headers (reduce (fn [m field]
                      (let [k (str/lower-case (.getName ^org.eclipse.jetty.http.HttpField field))
                            v (.getValue ^org.eclipse.jetty.http.HttpField field)]
                        (update m k #(if % (str % "," v) v)))) {} (.getHeaders request))
   :body (Request/asInputStream request)})

(defn- respond! [^Request request ^Response response ^Callback callback {:keys [status headers body]}]
  (let [data (if (bytes? body) body (.getBytes ^String (or body "") "UTF-8"))
        no-body? (or (= "HEAD" (.getMethod request)) (#{204 304} status))]
    (.setStatus response status)
    (doseq [[k v] headers] (.put (.getHeaders response) ^String k ^String v))
    (when-not (#{204 304} status) (.put (.getHeaders response) "Content-Length" (str (alength data))))
    (.write response true (when-not no-body? (ByteBuffer/wrap data)) callback)))

(defn- endpoint [^ExecutorService executor sessions {:keys [on-open send-timeout-ms] :or {send-timeout-ms 5000}}]
  (let [worker (atom nil) session-ref (atom nil)]
    (reify Session$Listener$AutoDemanding
      (onWebSocketOpen [_ session]
        (reset! session-ref session)
        (swap! sessions conj session)
        (.setIdleTimeout ^Session session (Duration/ofSeconds 90))
        (.setMaxOutgoingFrames ^Session session 2)
        (let [send! (fn [^bytes data]
                      (let [done (CompletableFuture.)]
                        (.sendBinary ^Session session (ByteBuffer/wrap data)
                          (reify org.eclipse.jetty.websocket.api.Callback
                            (succeed [_] (.complete done true))
                            (fail [_ error] (.completeExceptionally done error))))
                        (.get done (long send-timeout-ms) TimeUnit/MILLISECONDS)))
              ping! (fn [] (.sendPing ^Session session (ByteBuffer/allocate 0) org.eclipse.jetty.websocket.api.Callback/NOOP))]
          (reset! worker
            (.submit executor ^Runnable
              (fn []
                (try (on-open {:send! send! :ping! ping! :open? #(.isOpen ^Session session)
                               :close! #(.close ^Session session (int %1) ^String %2 org.eclipse.jetty.websocket.api.Callback/NOOP)})
                     (catch InterruptedException _ (.interrupt (Thread/currentThread)))
                     (catch Exception _ (.disconnect ^Session session))
                     (finally (swap! sessions disj session) (.disconnect ^Session session))))))))
      (onWebSocketClose [_ _ _]
        (when-let [session @session-ref] (swap! sessions disj session))
        (when-let [^Future task @worker] (.cancel task true)))
      (onWebSocketError [_ _]
        (when-let [^Session session @session-ref] (.disconnect session)))
      (onWebSocketText [_ _] nil)
      (onWebSocketBinary [_ _ callback]
        (.succeed ^org.eclipse.jetty.websocket.api.Callback callback)))))

(defn start!
  "HTTP and WebSocket adapter for Ring-shaped handlers. A :websocket response
  supplies :on-open, which runs on an owned virtual thread with bounded sends."
  [{:keys [host port]} handler]
  (let [executor (Executors/newVirtualThreadPerTaskExecutor)
        pool (doto (QueuedThreadPool.) (.setVirtualThreadsExecutor executor))
        server (Server. pool)
        connector (doto (ServerConnector. server) (.setHost host) (.setPort (int port)) (.setIdleTimeout 30000))
        context (ContextHandler. "/")
        sessions (atom #{}) stopped? (atom false)
        stop! (fn [] (when (compare-and-set! stopped? false true)
                       (doseq [^Session session @sessions] (.disconnect session))
                       (try (.stop server)
                            ;; Jetty closes connectors/resources even when the
                            ;; graceful drain reaches its configured deadline.
                            (catch java.util.concurrent.TimeoutException _)
                            (finally (.shutdownNow executor) (.awaitTermination executor 5 TimeUnit/SECONDS)))))]
    (try
      (.addConnector server connector)
      (.setHandler server context)
      (.setStopTimeout server 1000)
      (let [container (ServerWebSocketContainer/ensure server context)]
        (.setMaxBinaryMessageSize container 1024)
        (.setMaxTextMessageSize container 1024)
        (.setMaxFrameSize container 5000000)
        (.setHandler context
          (proxy [Handler$Abstract] []
            (handle [request response callback]
              (let [result (handler (request-map request))]
                (if-let [websocket (:websocket result)]
                  (when-not (.upgrade container (reify WebSocketCreator
                                                  (createWebSocket [_ _ _ _] (endpoint executor sessions websocket)))
                                      request response callback)
                    (respond! request response callback {:status 426 :headers {"Upgrade" "websocket"} :body "WebSocket upgrade required"}))
                  (respond! request response callback result)))
              true))))
      (.start server)
      {:port (.getLocalPort connector) :stop! stop!}
      (catch Throwable error (stop!) (throw error)))))
