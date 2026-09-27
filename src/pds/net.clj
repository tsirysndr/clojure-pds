(ns pds.net
  (:require [clojure.string :as str])
  (:import [java.io InputStream OutputStream]
           [java.net URI InetAddress InetSocketAddress]
           [java.util.concurrent CompletableFuture TimeUnit]
           [org.eclipse.jetty.client HttpClient BufferingResponseListener BytesRequestContent InputStreamResponseListener Result]
           [org.eclipse.jetty.http HttpCookieStore$Empty]
           [org.eclipse.jetty.util Promise SocketAddressResolver SocketAddressResolver$Async]))

(defn- in-prefix? [^bytes address ^bytes prefix bits]
  (and (= (alength address) (alength prefix))
       (every? (fn [i]
                 (let [mask (bit-and 255 (bit-shift-left 255 (- 8 (min 8 (- bits (* 8 i))))))]
                   (= (bit-and mask (aget address i)) (bit-and mask (aget prefix i)))))
               (range (quot (+ bits 7) 8)))))

(def blocked-prefixes
  (mapv (fn [[ip bits]] [(.getAddress (InetAddress/getByName ip)) bits])
        [["0.0.0.0" 8] ["10.0.0.0" 8] ["100.64.0.0" 10] ["127.0.0.0" 8]
         ["169.254.0.0" 16] ["172.16.0.0" 12] ["192.0.0.0" 24] ["192.0.2.0" 24]
         ["192.88.99.0" 24] ["192.168.0.0" 16] ["198.18.0.0" 15] ["198.51.100.0" 24]
         ["203.0.113.0" 24] ["224.0.0.0" 4] ["240.0.0.0" 4]
         ["2001::" 23] ["2001:db8::" 32] ["2002::" 16] ["3fff::" 20]]))

(defn public-address? [^InetAddress address]
  (let [bytes (.getAddress address)]
    (and (not (.isAnyLocalAddress address)) (not (.isLoopbackAddress address))
         (not (.isLinkLocalAddress address)) (not (.isSiteLocalAddress address)) (not (.isMulticastAddress address))
         (or (= 4 (alength bytes)) (in-prefix? bytes (.getAddress (InetAddress/getByName "2000::")) 3))
         (not-any? (fn [[prefix bits]] (in-prefix? bytes prefix bits)) blocked-prefixes))))

(defn https-uri! [value]
  (let [uri (try (URI/create value) (catch Exception _ nil))]
    (when-not (and uri (= "https" (.getScheme uri)) (seq (.getHost uri))
                   (nil? (.getUserInfo uri)) (nil? (.getFragment uri))
                   (<= (count value) 8192) (or (= -1 (.getPort uri)) (<= 1 (.getPort uri) 65535)))
      (throw (ex-info "Expected an absolute HTTPS URL without credentials or fragment" {:network-error :url})))
    uri))

(defn guarded-resolver [^SocketAddressResolver delegate allowed?]
  (reify SocketAddressResolver
    (resolve [_ host port context promise]
      (.resolve delegate host port context
        (reify Promise
          (succeeded [_ addresses]
            (if (and (seq addresses) (every? #(and (not (.isUnresolved ^InetSocketAddress %))
                                                                  (allowed? (.getAddress ^InetSocketAddress %))) addresses))
              ;; Hand the *resolved* socket addresses directly to the connector:
              ;; no second DNS lookup occurs between validation and connection.
              (.succeeded ^Promise promise
                (mapv (fn [^InetSocketAddress address]
                        ;; Keep the original hostname for SNI/certificate checks
                        ;; while binding the socket to the validated IP bytes.
                        (InetSocketAddress. (InetAddress/getByAddress host (.getAddress (.getAddress address))) port)) addresses))
              (.failed ^Promise promise (ex-info "Destination address is not public" {:network-error :address}))))
          (failed [_ error] (.failed ^Promise promise error)))))))

(defrecord Client [^HttpClient http uri-validator]
  java.io.Closeable
  (close [_] (.stop http)))

(defn open-client
  ([] (open-client {}))
  ([{:keys [resolver address-policy uri-validator ssl-context]
     :or {address-policy public-address? uri-validator https-uri!}}]
   ;; Overrides are dependency injection for isolated tests, never environment
   ;; flags. Production callers use the secure zero-argument constructor.
   (let [client (doto (HttpClient.) (.setFollowRedirects false)
                  (.setAddressResolutionTimeout 2000) (.setConnectTimeout 3000)
                  (.setIdleTimeout 5000) (.setMaxResponseHeadersSize 16384)
                  (.setMaxConnectionsPerDestination 4) (.setMaxRequestsQueuedPerDestination 16)
                  (.setMaxDestinations 256) (.setDestinationIdleTimeout 30000)
                  (.setHttpCookieStore (HttpCookieStore$Empty.)))]
     (try
       (when ssl-context (.setSslContextFactory client ssl-context))
       (.setSocketAddressResolver client
         (guarded-resolver
           (or resolver (reify SocketAddressResolver
                          (resolve [_ host port context promise]
                            (.resolve (SocketAddressResolver$Async. (.getExecutor client) (.getScheduler client) 2000)
                                      host port context promise)))) address-policy))
       (.start client)
       ;; Never enable transparent compression: callers bound the actual bytes
       ;; received, and reject encoded payloads instead of decompressing bombs.
       (.clear (.getContentDecoderFactories client))
       (->Client client uri-validator)
       (catch Throwable e (.stop client) (throw e))))))

(defn- request! [^HttpClient client ^URI uri maximum timeout-ms method body headers]
  (let [done (CompletableFuture.)
        request (-> (.newRequest client uri) (.method method) (.timeout (long timeout-ms) TimeUnit/MILLISECONDS))
        listener (proxy [BufferingResponseListener] [(int maximum)]
                   (onComplete [^Result result]
                     (if (.isFailed result)
                       (.completeExceptionally done (.getFailure result))
                       (let [response (.getResponse result)]
                         (.complete done {:status (.getStatus response)
                                          :headers (into {} (map (fn [field] [(str/lower-case (.getName ^org.eclipse.jetty.http.HttpField field))
                                                                             (.getValue ^org.eclipse.jetty.http.HttpField field)])) (.getHeaders response))
                                          :body (.getContent this)})))))]
    (try
      (when body (.body request (BytesRequestContent. (get headers "content-type" "application/json") (into-array (Class/forName "[B") [body]))))
      (.headers request (reify java.util.function.Consumer
                          (accept [_ fields]
                            (doseq [[name value] headers]
                              (.put ^org.eclipse.jetty.http.HttpFields$Mutable fields ^String name ^String value)))))
      (.send request listener)
      (.get done (long timeout-ms) TimeUnit/MILLISECONDS)
      (catch Exception e (.abort request e) (throw e)))))

(defn fetch!
  "Bounded public GET. Every redirect is validated and every new connection uses
  the guarded DNS resolver. No cookie jar, ambient credentials, or automatic redirects."
  ([client url] (fetch! client url {}))
  ([client url {:keys [maximum timeout-ms redirects] :or {maximum 1048576 timeout-ms 10000 redirects 3}}]
   (let [deadline (+ (System/nanoTime) (* 1000000 timeout-ms))]
     (loop [uri ((:uri-validator client) url) hops 0]
       (let [remaining (quot (- deadline (System/nanoTime)) 1000000)]
         (when-not (pos? remaining) (throw (java.util.concurrent.TimeoutException. "Outbound request deadline")))
         (let [{:keys [status headers] :as response} (request! (:http client) uri maximum remaining "GET" nil {})]
           (when-let [encoding (get headers "content-encoding")]
             (when-not (= "identity" (str/lower-case encoding))
               (throw (ex-info "Encoded response is unsupported" {:network-error :encoding}))))
           (if (#{301 302 303 307 308} status)
             (do (when (>= hops redirects) (throw (ex-info "Redirect limit exceeded" {:network-error :redirect})))
                 (let [location (get headers "location")]
                   (when-not location (throw (ex-info "Redirect has no location" {:network-error :redirect})))
                   (recur ((:uri-validator client) (str (.resolve ^URI uri ^String location))) (inc hops))))
             response)))))))

(defn post-json!
  "Submit bounded JSON bytes to exactly one HTTPS destination. Never follows
  redirects or retries automatically: the caller must reconcile an ambiguous
  outcome before retrying a mutation. The response body is also bounded."
  ([client url body] (post-json! client url body {}))
  ([client url body {:keys [maximum timeout-ms] :or {maximum 65536 timeout-ms 5000}}]
   (when-not (and (bytes? body) (<= (alength ^bytes body) 65536))
     (throw (ex-info "Outbound JSON body exceeds 65536 bytes" {:network-error :body})))
   (let [uri ((:uri-validator client) url)
         response (request! (:http client) uri maximum timeout-ms "POST" body {})
         encoding (get-in response [:headers "content-encoding"])]
     (when (and encoding (not= "identity" (str/lower-case encoding)))
       (throw (ex-info "Encoded response is unsupported" {:network-error :encoding})))
     response)))

(defn- exchange-options! [method headers body maximum timeout-ms]
  (when-not (and (#{"GET" "HEAD" "POST"} method) (<= 1 maximum 67108864) (<= 1 timeout-ms 60000)
                 (or (nil? body) (and (= method "POST") (bytes? body) (<= (alength ^bytes body) 67108864)))
                 (every? (fn [[name value]]
                           (and (string? name) (re-matches #"[a-z0-9!#$%&'*+.^_`|~-]+" name)
                                (string? value) (<= (count value) 8192) (re-matches #"[\t\x20-\x7e]*" value))) headers)
                 (not-any? #(contains? headers %) ["host" "connection" "transfer-encoding" "content-length" "cookie" "proxy-authorization"]))
    (throw (ex-info "Invalid outbound exchange" {:network-error :request}))))

(defn exchange!
  "One bounded HTTPS exchange. Callers supply an explicit header allowlist and
  replacement credentials. No redirects, cookies, decompression or retries."
  [client url {:keys [method headers body maximum timeout-ms]
               :or {headers {} maximum 10485760 timeout-ms 10000}}]
  (exchange-options! method headers body maximum timeout-ms)
  (let [uri ((:uri-validator client) url)
        response (request! (:http client) uri maximum timeout-ms method body (assoc headers "accept-encoding" "identity"))
        encoding (get-in response [:headers "content-encoding"])]
    (when (and encoding (not= "identity" (str/lower-case encoding)))
      (throw (ex-info "Encoded response is unsupported" {:network-error :encoding})))
    response))

(defn exchange-to!
  "One HTTPS exchange, copying the bounded response to a caller-owned output.
  Returns status, headers and actual body size only after complete success. The
  caller must stage writes: late transport/size errors may follow a valid prefix.
  Uses the same guarded client/credentials policy as exchange!, with no redirects,
  decompression, cookies or retries. Does not close the output."
  [client url {:keys [method headers body maximum timeout-ms]
               :or {headers {} maximum 10485760 timeout-ms 10000}} ^OutputStream out]
  (exchange-options! method headers body maximum timeout-ms)
  (let [uri ((:uri-validator client) url)
        deadline (+ (System/nanoTime) (* 1000000 timeout-ms))
        remaining! (fn []
                     (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
                     (let [left (- deadline (System/nanoTime))]
                       (when-not (pos? left) (throw (java.util.concurrent.TimeoutException. "Outbound request deadline")))
                       left))
        request (-> (.newRequest ^HttpClient (:http client) ^URI uri)
                    (.method method) (.timeout (long timeout-ms) TimeUnit/MILLISECONDS))]
    (with-open [listener (InputStreamResponseListener.)]
      (try
        (when body (.body request (BytesRequestContent. (get headers "content-type" "application/json") (into-array (Class/forName "[B") [body]))))
        (.headers request (reify java.util.function.Consumer
                            (accept [_ fields]
                              (doseq [[name value] (assoc headers "accept-encoding" "identity")]
                                (.put ^org.eclipse.jetty.http.HttpFields$Mutable fields ^String name ^String value)))))
        (.send request listener)
        (let [response (.get listener (long (remaining!)) TimeUnit/NANOSECONDS)
              status (.getStatus response)
              headers (into {} (map (fn [field] [(str/lower-case (.getName ^org.eclipse.jetty.http.HttpField field))
                                                 (.getValue ^org.eclipse.jetty.http.HttpField field)])) (.getHeaders response))
              encoding (get headers "content-encoding")
              bodyless? (or (= method "HEAD") (#{204 304} status))
              declared (when-let [value (and (not bodyless?) (get headers "content-length"))]
                         (when-not (re-matches #"[0-9]{1,18}" value)
                           (throw (ex-info "Invalid response length" {:network-error :length})))
                         (Long/parseLong value))]
          (when (and encoding (not= "identity" (str/lower-case encoding)))
            (throw (ex-info "Encoded response is unsupported" {:network-error :encoding})))
          (when (and declared (> declared maximum))
            (throw (ex-info "Response exceeds the byte limit" {:network-error :size})))
          (with-open [^InputStream input (.getInputStream listener)]
            (let [buffer (byte-array 65536)
                  size (loop [size 0]
                         (remaining!)
                         (let [n (.read input buffer 0 (int (min (alength buffer) (inc (- maximum size)))))]
                           (cond
                             (= n -1) size
                             (or (zero? n) (> (+ size n) maximum))
                             (throw (ex-info "Response exceeds the byte limit or stopped making progress" {:network-error :size}))
                             :else (do (.write out buffer 0 n) (recur (+ size n))))))
                  result (.await listener (long (remaining!)) TimeUnit/NANOSECONDS)]
              (when (.isFailed result) (throw (.getFailure result)))
              (when (and declared (not= declared size))
                (throw (ex-info "Incomplete response body" {:network-error :length})))
              {:status status :headers headers :size size})))
        (catch Throwable error (.abort request error) (throw error))))))
