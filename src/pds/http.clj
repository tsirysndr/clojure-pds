(ns pds.http
  (:require [clojure.string :as str]
            [pds.response-body :as body])
  (:import [java.io Closeable IOException InputStream]
           [java.nio ByteBuffer]
           [java.time Duration]
           [java.util.concurrent CompletableFuture Executors ExecutorService Future FutureTask TimeUnit]
           [java.util.function Consumer]
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

(defn- respond-buffered! [^Request request ^Response response ^Callback callback {:keys [status headers body]}]
  (let [data (if (bytes? body) body (.getBytes ^String (or body "") "UTF-8"))
        no-body? (or (= "HEAD" (.getMethod request)) (#{204 304} status))]
    (.setStatus response status)
    (doseq [[k v] headers] (.put (.getHeaders response) ^String k ^String v))
    (when-not (or (#{204 304} status)
                  (and (= "HEAD" (.getMethod request)) (.contains (.getHeaders response) "Content-Length")))
      (.put (.getHeaders response) "Content-Length" (str (alength data))))
    (.write response true (when-not no-body? (ByteBuffer/wrap data)) callback)))

(defn- write-chunk! [^Response response last? buffer]
  (let [done (CompletableFuture.)]
    (.write response last? buffer
      (reify Callback
        (succeeded [_] (.complete done true))
        (failed [_ error] (.completeExceptionally done error))))
    ;; No buffer reuse or next read until Jetty releases this chunk. A stalled
    ;; client cannot keep an owned source alive indefinitely.
    (.get done 30 TimeUnit/SECONDS)))

(defn- stream-content! [^Response response {:keys [input length]}]
  (let [^InputStream input input buffer (byte-array 65536)]
    (loop [remaining length]
      (if (zero? remaining)
        (do (when-not (= -1 (.read input))
              (throw (IOException. "Response body exceeds its declared length")))
            (write-chunk! response true nil))
        (let [n (.read input buffer 0 (int (min remaining (alength buffer))))]
          (when (<= n 0) (throw (IOException. "Response body ended before its declared length")))
          ;; Keep the final chunk until EOF is checked, so a wrong length does
          ;; not become a successfully completed response.
          (when (= n remaining)
            (when-not (= -1 (.read input))
              (throw (IOException. "Response body exceeds its declared length"))))
          (write-chunk! response (= n remaining) (ByteBuffer/wrap buffer 0 n))
          (when (< n remaining) (recur (- remaining n))))))))

(defn- respond-stream! [^ExecutorService executor streams stopped? ^Request request ^Response response
                       ^Callback callback {:keys [status headers body]}]
  (let [finished? (atom false) task (atom nil) cancel-ref (atom nil)
        finish! (fn [error]
                  (when (compare-and-set! finished? false true)
                    (swap! streams disj @cancel-ref)
                    (let [error (try (.close ^Closeable body) error (catch Throwable close-error (or error close-error)))]
                      (if error (.failed callback error) (.succeeded callback)))))
        cancel! (fn [error]
                  (finish! error)
                  (when-let [^Future worker @task] (.cancel worker true)))
        worker (FutureTask.
                ^Runnable
                (fn []
                  (try
                    (.setStatus response status)
                    (doseq [[k v] headers] (.put (.getHeaders response) ^String k ^String v))
                    (when-not (#{204 304} status)
                      (.put (.getHeaders response) "Content-Length" (str (:length body))))
                    (if (or (= "HEAD" (.getMethod request)) (#{204 304} status))
                      (write-chunk! response true nil)
                      (stream-content! response body))
                    (finish! nil)
                    (catch Throwable error (finish! error)))) nil)]
    (reset! cancel-ref cancel!)
    (reset! task worker)
    (swap! streams conj cancel!)
    (try
      (.addFailureListener request (reify Consumer (accept [_ error] (cancel! error))))
      (if @stopped?
        (cancel! (IOException. "Server is stopping"))
        (.execute executor worker))
      (catch Throwable error (cancel! error)))))

(defn- respond! [executor streams stopped? request response callback result]
  (if (body/stream? (:body result))
    (respond-stream! executor streams stopped? request response callback result)
    (respond-buffered! request response callback result)))

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
                     (when (.isOpen ^Session session)
                       (let [closed (CompletableFuture.)]
                         (.close ^Session session 1000 "Stream ended"
                           (reify org.eclipse.jetty.websocket.api.Callback
                             (succeed [_] (.complete closed true))
                             (fail [_ error] (.completeExceptionally closed error))))
                         (.get closed (long send-timeout-ms) TimeUnit/MILLISECONDS)))
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
        sessions (atom #{}) streams (atom #{}) stopped? (atom false)
        stop! (fn [] (when (compare-and-set! stopped? false true)
                       (doseq [^Session session @sessions] (.disconnect session))
                       (doseq [cancel! @streams] (cancel! (IOException. "Server is stopping")))
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
                    (respond! executor streams stopped? request response callback {:status 426 :headers {"Upgrade" "websocket"} :body "WebSocket upgrade required"}))
                  (respond! executor streams stopped? request response callback result)))
              true))))
      (.start server)
      {:port (.getLocalPort connector) :stop! stop!}
      (catch Throwable error (stop!) (throw error)))))
