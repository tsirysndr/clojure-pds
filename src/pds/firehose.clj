(ns pds.firehose
  (:require [clojure.string :as str]
            [pds.db :as db]
            [pds.protocol.codec :as codec]
            [pds.request :as request]
            [pds.xrpc :as xrpc])
  (:import [java.io ByteArrayOutputStream]
           [java.time Instant]
           [java.util.concurrent Semaphore TimeoutException]))

(defn settings [env]
  (into {} (map (fn [[key variable default maximum]]
                  (let [value (get env variable (str default))
                        number (when (and (string? value) (re-matches #"[0-9]{1,8}" value)) (Long/parseLong value))]
                    (when-not (and number (<= 1 number maximum))
                      (throw (ex-info (str variable " is out of range") {})))
                    [key number]))
                [[:firehose-max-clients "PDS_FIREHOSE_MAX_CLIENTS" 64 10000]
                 [:firehose-backfill-seconds "PDS_FIREHOSE_BACKFILL_SECONDS" 86400 2592000]
                 [:firehose-max-backlog "PDS_FIREHOSE_MAX_BACKLOG" 1000 1000000]])))

(defn frame [header payload]
  (let [out (ByteArrayOutputStream.)]
    (.write out ^bytes (codec/encode header))
    (.write out ^bytes (codec/encode payload))
    (when (> (.size out) 5000000) (throw (ex-info "Event exceeds frame limit" {})))
    (.toByteArray out)))

(defn message [type payload] (frame {"op" 1 "t" (str "#" type)} payload))
(defn fail! [send! name text] (send! (frame {"op" -1} {"error" name "message" text})))

(defn cursor! [request]
  (let [params (try (request/query-params request)
                    (catch clojure.lang.ExceptionInfo _ (throw (ex-info "Invalid query parameters" {:stream-error "InvalidRequest"}))))
        value (get params "cursor")]
    (when (some? value)
      (when-not (and (re-matches #"[0-9]{1,16}" value) (<= (Long/parseLong value) 9007199254740991))
        (throw (ex-info "Invalid cursor" {:stream-error "InvalidRequest"})))
      (Long/parseLong value))))

(defn bounds [ds cutoff]
  (with-open [conn (db/connection ds)]
    (first (db/query conn "SELECT coalesce(max(seq),0) AS high, min(seq) FILTER (WHERE created_at >= ?) AS oldest FROM repo_events" cutoff))))

(defn next-rows [ds cursor cutoff]
  (with-open [conn (db/connection ds)]
    (db/query conn "SELECT seq FROM repo_events WHERE seq > ? AND created_at >= ? ORDER BY seq LIMIT 32" cursor cutoff)))

(defn event [ds seq]
  ;; Recheck availability for each frame, not merely at connection time.
  (with-open [conn (db/connection ds)]
    (when-let [row (first (db/query conn "SELECT e.seq, e.event_type, e.payload FROM repo_events e JOIN accounts a ON a.did = e.did
                                         WHERE e.seq = ? AND (e.event_type IN ('account','identity') OR a.status = 'active')" seq))]
      (when (:payload row)
        (message (:event_type row) (assoc (codec/decode (:payload row) 5000000) "seq" (:seq row)))))))

(defn backlog-exceeded? [ds cursor high maximum]
  (with-open [conn (db/connection ds)]
    (> (:n (first (db/query conn "SELECT count(*) AS n FROM (SELECT seq FROM repo_events WHERE seq > ? ORDER BY seq LIMIT ?) pending"
                            (max cursor high) (inc maximum)))) maximum)))

(defn stream! [ds settings request {:keys [send! ping! open?]}]
  (let [cursor (cursor! request)
        cutoff (.minusSeconds (Instant/now) (:firehose-backfill-seconds settings))
        {:keys [high oldest]} (or (:firehose-bounds request) (bounds ds cutoff))]
    (if (and cursor (> cursor high))
      (fail! send! "FutureCursor" "Cursor is ahead of this stream")
      (let [outdated? (and cursor (pos? cursor) (< cursor (dec (or oldest (inc high)))))
            start (cond (nil? cursor) high (or outdated? (zero? cursor)) (dec (or oldest (inc high))) :else cursor)]
        (when outdated? (send! (message "info" {"name" "OutdatedCursor" "message" "Requested cursor is outside the backfill window"})))
        (loop [cursor start last-ping (System/nanoTime)]
          (when (and (open?) (not (.isInterrupted (Thread/currentThread))))
            (when (backlog-exceeded? ds cursor high (:firehose-max-backlog settings))
              (throw (ex-info "Consumer cannot keep up" {:stream-error "ConsumerTooSlow"})))
            (let [rows (next-rows ds cursor cutoff)]
              (doseq [{:keys [seq]} rows]
                (when-let [data (event ds seq)] (send! data)))
              (when (empty? rows) (Thread/sleep 250))
              (let [ping? (> (- (System/nanoTime) last-ping) 20000000000)]
                (when ping? (ping!))
                (recur (or (:seq (last rows)) cursor) (if ping? (System/nanoTime) last-ping))))))))))

(defn routes [ds supplied-settings]
  (let [settings (merge (settings {}) supplied-settings)
        clients (Semaphore. (int (:firehose-max-clients settings)))]
    {"/xrpc/com.atproto.sync.subscribeRepos"
     {:method :get
      :handler
      (fn [request]
        (cond
          (not= :get (:request-method request)) (assoc-in (xrpc/error-response 405 "MethodNotAllowed" "Use GET") [:headers "Allow"] "GET")
          (not= "websocket" (str/lower-case (get-in request [:headers "upgrade"] "")))
          (assoc-in (xrpc/error-response 426 "UpgradeRequired" "WebSocket upgrade required") [:headers "Upgrade"] "websocket")
          :else
          (let [request (assoc request :firehose-bounds (bounds ds (.minusSeconds (Instant/now) (:firehose-backfill-seconds settings))))]
          {:websocket
           {:on-open
            (fn [{:keys [send!] :as connection}]
              (if-not (.tryAcquire clients)
                (fail! send! "ConsumerTooSlow" "Connection limit reached")
                (try
                  (stream! ds settings request connection)
                  (catch InterruptedException e (throw e))
                  (catch TimeoutException _ (try (fail! send! "ConsumerTooSlow" "Send deadline exceeded") (catch Exception _)))
                  (catch Exception error
                    (try (fail! send! (or (:stream-error (ex-data error)) "InternalServerError")
                                (if (:stream-error (ex-data error)) (.getMessage error) "Stream unavailable"))
                         (catch Exception _)))
                  (finally (.release clients)))))}})))}}))
