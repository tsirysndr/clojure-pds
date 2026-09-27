(ns pds.relay
  "Opt-in relay discovery. Durable, fenced leases coordinate bounded requests;
  no database transaction spans HTTP and failures never block repository writes."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.db :as db]
            [pds.net :as net]
            [pds.protocol.codec :as codec])
  (:import [java.net URI]
           [java.time Instant ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util UUID]
           [java.util.concurrent Executors TimeUnit]))

(defn- invalid! [message] (throw (ex-info message {})))
(defn- origin [value]
  (try
    (let [uri (net/https-uri! value)]
      (when-not (and (#{"" "/"} (.getRawPath uri)) (nil? (.getRawQuery uri))) (invalid! "origin"))
      (str "https://" (str/lower-case (.getHost uri))
           (when-not (#{-1 443} (.getPort uri)) (str ":" (.getPort uri)))))
    (catch Exception _ (invalid! "PDS_RELAY_URLS must contain HTTPS origins without credentials, query, fragment or path"))))
(defn settings [env]
  (let [urls (get env "PDS_RELAY_URLS" "") interval (get env "PDS_RELAY_INTERVAL_SECONDS" "1200")]
    (when-not (and (string? urls) (<= (count urls) 8192)) (invalid! "PDS_RELAY_URLS is too long"))
    (when-not (and (string? interval) (re-matches #"[0-9]{1,5}" interval) (<= 60 (Long/parseLong interval) 86400))
      (invalid! "PDS_RELAY_INTERVAL_SECONDS must be 60 to 86400"))
    (let [values (if (str/blank? urls) [] (str/split urls #"," -1))]
      (when (> (count values) 16) (invalid! "PDS_RELAY_URLS supports at most 16 relays"))
      {:relay-urls (vec (distinct (map #(origin (str/trim %)) values)))
       :relay-interval-seconds (Long/parseLong interval)})))
(defn hostname! [settings]
  (let [uri (try (URI/create (:public-url settings)) (catch Exception _ nil))]
    ;; requestCrawl names a hostname, not a full endpoint. Do not announce an
    ;; origin whose nonstandard port/path would be lost in that representation.
    (when-not (and uri (= "https" (.getScheme uri)) (seq (.getHost uri))
                   (#{-1 443} (.getPort uri)) (#{"" "/"} (.getRawPath uri))
                   (nil? (.getRawQuery uri)) (nil? (.getUserInfo uri)) (nil? (.getFragment uri)))
      (invalid! "Relay announcements require PDS_PUBLIC_URL to be an HTTPS origin on port 443"))
    (str/lower-case (.getHost uri))))
(defn seed! [ds settings]
  (when (seq (:relay-urls settings))
    (let [hostname (hostname! settings)]
      (db/transact! ds
        (fn [conn]
          (db/execute! conn "SET LOCAL statement_timeout = '5s'")
          (doseq [url (:relay-urls settings)]
            (db/execute! conn "INSERT INTO relay_announcements(relay_url, hostname) VALUES (?, ?) ON CONFLICT DO NOTHING" url hostname)))))))
(defn- claim! [ds settings]
  (when (seq (:relay-urls settings))
    (db/transact! ds
      (fn [conn]
        (db/execute! conn "SET LOCAL statement_timeout = '5s'")
        (when-let [row (first (apply db/query conn
                                    (str "SELECT * FROM relay_announcements WHERE hostname = ? AND relay_url IN ("
                                         (str/join "," (repeat (count (:relay-urls settings)) "?"))
                                         ") AND next_attempt_at <= now() AND (lease_until IS NULL OR lease_until <= now())
                                          ORDER BY next_attempt_at, relay_url LIMIT 1 FOR UPDATE SKIP LOCKED")
                                    (hostname! settings) (:relay-urls settings)))]
          (let [id (UUID/randomUUID)]
            (db/execute! conn "UPDATE relay_announcements SET lease_id = ?, lease_until = now() + interval '30 seconds'
                               WHERE relay_url = ? AND hostname = ?" id (:relay_url row) (:hostname row))
            (assoc row :lease_id id)))))))
(defn retry-after
  "Return a bounded delay for a valid Retry-After, or nil. Remote text is never logged."
  [value now]
  (try
    (when (and (string? value) (<= (count value) 128))
      (let [seconds (if (re-matches #"[0-9]{1,10}" value)
                      (Long/parseLong value)
                      (- (.getEpochSecond (.toInstant (ZonedDateTime/parse value DateTimeFormatter/RFC_1123_DATE_TIME)))
                         (.getEpochSecond ^Instant now)))]
        (max 5 (min 86400 seconds))))
    (catch Exception _ nil)))
(defn deliver! [settings job]
  (try
    (let [post (or (:relay-post settings) #(net/post-json! (:http-client settings) %1 %2 %3))
          result (post (str (:relay_url job) "/xrpc/com.atproto.sync.requestCrawl")
                       (codec/utf8 (json/write-str {"hostname" (:hostname job)}))
                       {:maximum 65536 :timeout-ms 5000})
          status (:status result)]
      {:success? (and (integer? status) (<= 200 status 299)) :status status
       :error "http-error"
       :retry-after (when (#{429 503} status) (retry-after (get-in result [:headers "retry-after"]) (Instant/now)))})
    (catch InterruptedException e (throw e))
    (catch Exception _ {:success? false :error "transport-error"})))
(defn- finish! [ds settings job result]
  (let [success? (:success? result) attempts (if success? 0 (min 30 (inc (:attempts job))))
        delay (if success? (:relay-interval-seconds settings 1200)
                  (max (or (:retry-after result) 0) (min 3600 (* 5 (bit-shift-left 1 (min 10 (dec attempts)))))))]
    (db/transact! ds
      (fn [conn]
        (db/execute! conn "SET LOCAL statement_timeout = '5s'")
        ;; A worker whose lease was reclaimed cannot replace the winner's state.
        (db/execute! conn "UPDATE relay_announcements SET attempts = ?, next_attempt_at = now() + (? * interval '1 second'),
                           lease_id = NULL, lease_until = NULL, last_status = ?, last_error = ?,
                           last_success_at = CASE WHEN ? THEN now() ELSE last_success_at END
                           WHERE relay_url = ? AND hostname = ? AND lease_id = ?"
                     attempts delay (:status result) (when-not success? (:error result)) (boolean success?)
                     (:relay_url job) (:hostname job) (:lease_id job))))))
(defn process-one! [ds settings]
  (when-let [job (claim! ds settings)]
    (let [result (deliver! settings job)]
      (when (= 1 (finish! ds settings job result))
        (if (:success? result) :announced :retry)))))
(defn start! [ds settings]
  (if-not (seq (:relay-urls settings)) (fn [] nil)
    (do
      (seed! ds settings)
      (let [executor (Executors/newSingleThreadScheduledExecutor)]
        (.scheduleWithFixedDelay executor
          ^Runnable (fn [] (try (process-one! ds settings)
                               (catch InterruptedException _ (.interrupt (Thread/currentThread)))
                               (catch Exception _ (binding [*out* *err*] (println "Relay announcement worker failed; retrying")))))
          0 1 TimeUnit/SECONDS)
        (fn [] (.shutdownNow executor) (.awaitTermination executor 15 TimeUnit/SECONDS))))))
