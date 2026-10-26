(ns dagger.connection
  "Closeable Dagger sessions and synchronous JDK HTTP transport."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io Closeable]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util Base64]
           [java.util.concurrent TimeUnit]))

(defrecord Client [transport cleanup closed?]
  Closeable
  (close [_] (when (compare-and-set! closed? false true) (cleanup))))

(defmethod print-method Client [_ ^java.io.Writer writer]
  (.write writer "#dagger/client{}"))

(defn with-transport
  "Create a Closeable client from a function accepting {:query ... :variables ...}
  and returning {:status integer :body JSON-string}. Useful for engine-free tests."
  ([transport] (with-transport transport (constantly nil)))
  ([transport cleanup] (->Client transport cleanup (atom false))))

(defn request!
  "Execute a GraphQL document. Return its data map (string keys), or throw ex-info.
  GraphQL errors preserve :errors, including engine extensions and execution details."
  ([client document] (request! client document {}))
  ([client document variables]
   (when @(:closed? client)
     (throw (ex-info "Dagger client is closed" {:type :dagger/closed})))
   (let [{:keys [status body]} ((:transport client) {:query document :variables variables})]
     (when-not (and (integer? status) (<= 200 status 299))
       (throw (ex-info "Dagger HTTP request failed" {:type :dagger/http :status status :body body})))
     (let [response (try (json/read-str body)
                         (catch Exception e
                           (throw (ex-info "Invalid JSON from Dagger" {:type :dagger/decode} e))))]
       (when-not (map? response)
         (throw (ex-info "Expected a GraphQL response object" {:type :dagger/decode})))
       (when (and (contains? response "errors") (not (sequential? (get response "errors"))))
         (throw (ex-info "Invalid GraphQL errors" {:type :dagger/decode})))
       (when (seq (get response "errors"))
         (throw (ex-info (str/join "; " (map #(get % "message" "GraphQL error") (get response "errors")))
                         {:type :dagger/graphql :errors (get response "errors")})))
       (when-not (contains? response "data")
         (throw (ex-info "Missing GraphQL data" {:type :dagger/decode})))
       (get response "data")))))

(defn- http-transport [port token timeout-ms]
  (let [http (-> (HttpClient/newBuilder)
                 (.connectTimeout (Duration/ofMillis timeout-ms))
                 (.followRedirects HttpClient$Redirect/NEVER)
                 (.version HttpClient$Version/HTTP_1_1)
                 (.build))
        auth (str "Basic " (.encodeToString (Base64/getEncoder)
                                            (.getBytes (str token ":") StandardCharsets/UTF_8)))
        endpoint (URI/create (str "http://127.0.0.1:" port "/query"))]
    (fn [payload]
      (let [request (-> (HttpRequest/newBuilder endpoint)
                        (.timeout (Duration/ofMillis timeout-ms))
                        (.header "Authorization" auth)
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString (json/write-str payload)))
                        (.build))]
        (try
          (let [response (.send http request (HttpResponse$BodyHandlers/ofString))]
            {:status (.statusCode response) :body (.body response)})
          (catch InterruptedException e
            (.interrupt (Thread/currentThread))
            (throw (ex-info "Dagger request interrupted" {:type :dagger/interrupted} e)))
          (catch java.io.IOException e
            (throw (ex-info "Dagger request failed" {:type :dagger/transport} e))))))))

(defn- validate-session [port token]
  (let [port (try (Long/parseLong (str port))
                  (catch Exception _ nil))]
    (when-not (and port (<= 1 port 65535) (string? token) (not (str/blank? token)))
      (throw (ex-info "Invalid Dagger session port or token" {:type :dagger/connection})))
    port))

(defn- stop-process! [^Process process]
  (try (.close (.getOutputStream process)) (catch java.io.IOException _))
  (when-not (.waitFor process 2 TimeUnit/SECONDS)
    (.destroy process)
    (when-not (.waitFor process 2 TimeUnit/SECONDS) (.destroyForcibly process))))

(defn- start-session! [{:keys [cli workdir connect-timeout-ms]}]
  (let [command (cond-> [cli "session" "--label" "dagger.io/sdk.name:clojure"
                        "--label" "dagger.io/sdk.version:0.1.0"]
                  workdir (into ["--workdir" (str workdir)]))
        process (try (.start (doto (ProcessBuilder. ^java.util.List command)
                              (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)))
                     (catch java.io.IOException e
                       (throw (ex-info "Cannot start Dagger CLI; install dagger or set :cli"
                                       {:type :dagger/connection :cli cli} e))))
        ready (promise)
        reader (Thread.
                (fn []
                  (try
                    (with-open [stream (io/reader (.getInputStream process))]
                      (doseq [line (line-seq stream)]
                        (when-not (realized? ready)
                          (let [value (try (json/read-str line) (catch Exception _ nil))]
                            (when (and (map? value) (contains? value "port")
                                       (contains? value "session_token"))
                              (deliver ready value))))))
                    (deliver ready {:error "Dagger CLI exited before establishing a session"})
                    (catch Exception _ (deliver ready {:error "Dagger session output closed"}))))
                "dagger-session-output")]
    (.setDaemon reader true)
    (.start reader)
    (try
      (let [credentials (deref ready connect-timeout-ms ::timeout)]
        (when (= credentials ::timeout)
          (throw (ex-info "Timed out starting Dagger session" {:type :dagger/timeout})))
        (when (:error credentials)
          (throw (ex-info (:error credentials) {:type :dagger/connection})))
        (validate-session (get credentials "port") (get credentials "session_token"))
        {:port (get credentials "port") :token (get credentials "session_token")
         :cleanup #(stop-process! process)})
      (catch Throwable e
        (stop-process! process)
        (throw e)))))

(defn connect
  "Connect to an inherited Dagger session or start `dagger session`.
  Use with-open to close owned sessions. Options: :port/:token, :cli, :workdir,
  :timeout-ms and :connect-timeout-ms. Explicit sessions never terminate the CLI."
  ([] (connect {}))
  ([options]
   (let [opts (merge {:timeout-ms 300000 :connect-timeout-ms 300000
                      :cli (or (System/getenv "_EXPERIMENTAL_DAGGER_CLI_BIN") "dagger")}
                     options)
         explicit? (or (contains? opts :port) (contains? opts :token))
         port (if explicit? (:port opts) (System/getenv "DAGGER_SESSION_PORT"))
         token (if explicit? (:token opts) (System/getenv "DAGGER_SESSION_TOKEN"))]
     (doseq [key [:timeout-ms :connect-timeout-ms]]
       (when-not (and (integer? (key opts)) (pos? (key opts)))
         (throw (ex-info "Timeout must be a positive number of milliseconds" {:type :dagger/connection :option key}))))
     (when (and (or port token) (:workdir opts))
       (throw (ex-info "Cannot change workdir on an existing session" {:type :dagger/connection})))
     (let [{:keys [port token cleanup]} (if (or explicit? port token)
                                        {:port (validate-session port token) :token token :cleanup (constantly nil)}
                                        (start-session! opts))]
       (try (with-transport (http-transport port token (:timeout-ms opts)) cleanup)
            (catch Throwable e (cleanup) (throw e)))))))
