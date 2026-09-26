(ns pds.redis
  (:require [pds.protocol.codec :as codec]
            [pds.rate-limit :as rate-limit])
  (:import [java.net URI]
           [java.time Duration]
           [javax.net.ssl SSLParameters]
           [redis.clients.jedis ConnectionPoolConfig DefaultJedisClientConfig RedisClient]
           [redis.clients.jedis.util JedisURIHelper]))

(defn- config-error! [message] (throw (ex-info message {})))
(defn- integer-setting [env key default maximum]
  (let [value (try (Long/parseLong (get env key (str default))) (catch Exception _ 0))]
    (when-not (<= 1 value maximum) (config-error! (str key " must be between 1 and " maximum)))
    value))

(defn settings [env]
  (let [backend (get env "PDS_RATE_LIMIT_BACKEND" "memory")
        options {:backend backend :max-requests (integer-setting env "PDS_RATE_LIMIT_REQUESTS" 120 1000000)
                 :window-ms (* 1000 (integer-setting env "PDS_RATE_LIMIT_WINDOW_SECONDS" 60 3600))}
        write-enabled (get env "PDS_RECORD_WRITE_RATE_LIMIT_ENABLED" "true")
        _ (when-not (#{"true" "false"} write-enabled)
            (config-error! "PDS_RECORD_WRITE_RATE_LIMIT_ENABLED must be true or false"))
        options (if (some #(contains? env %) ["PDS_RECORD_WRITE_RATE_LIMIT_ENABLED"
                                             "PDS_RECORD_WRITE_RATE_LIMIT_REQUESTS"
                                             "PDS_RECORD_WRITE_RATE_LIMIT_WINDOW_SECONDS"])
                  (assoc options :record-writes
                         {:enabled (= "true" write-enabled)
                          :max-requests (integer-setting env "PDS_RECORD_WRITE_RATE_LIMIT_REQUESTS" (:max-requests options) 1000000)
                          :window-ms (* 1000 (integer-setting env "PDS_RECORD_WRITE_RATE_LIMIT_WINDOW_SECONDS" (quot (:window-ms options) 1000) 86400))})
                  options)]
    (when-not (#{"memory" "redis"} backend) (config-error! "PDS_RATE_LIMIT_BACKEND must be memory or redis"))
    (if (= "memory" backend) options
        (let [^URI uri (try (URI. (get env "PDS_REDIS_URL" "")) (catch Exception _ nil))
              prefix (get env "PDS_REDIS_PREFIX" "clojure-pds")]
          (when-not (and uri (#{"redis" "rediss"} (.getScheme uri)) (.getHost uri)
                         (or (= -1 (.getPort uri)) (<= 1 (.getPort uri) 65535))
                         (nil? (.getQuery uri)) (nil? (.getFragment uri))
                         (re-matches #"(?:/[0-9]{1,5})?" (.getPath uri)))
            (config-error! "PDS_REDIS_URL must be redis:// or rediss:// with an optional database number"))
          (when-not (and (string? prefix) (re-matches #"[A-Za-z0-9:_-]{1,128}" prefix))
            (config-error! "PDS_REDIS_PREFIX must be 1 to 128 safe namespace characters"))
          (assoc options :uri uri :prefix prefix)))))

(def script
  ;; TTL and increment are atomic and use the Redis server clock. Rejected
  ;; requests do not extend the window or grow the counter.
  "local ttl = redis.call('PTTL', KEYS[1])
   if ttl <= 0 then
     redis.call('SET', KEYS[1], 1, 'PX', ARGV[2])
     return {1, tonumber(ARGV[2])}
   end
   if tonumber(redis.call('GET', KEYS[1])) >= tonumber(ARGV[1]) then
     return {0, ttl}
   end
   redis.call('INCR', KEYS[1])
   return {1, ttl}")

(defn bucket-key [prefix address]
  (str prefix ":rate:" (codec/base32 (codec/sha256 (codec/utf8 address)))))

(defrecord RedisLimiter [^RedisClient client prefix max-requests window-ms]
  rate-limit/Limiter
  (admit! [_ address]
    (let [[allowed remaining] (.eval client script ^java.util.List [(bucket-key prefix address)]
                                     ^java.util.List [(str max-requests) (str window-ms)])]
      {:allowed? (= 1 allowed) :retry-after (max 1 (long (Math/ceil (/ remaining 1000.0))))}))
  java.io.Closeable
  (close [_] (.close client)))

(defn- open-base-limiter [{:keys [backend uri prefix max-requests window-ms] :as options}]
  (if (= "memory" backend)
    (rate-limit/memory-limiter options)
    (let [tls (doto (SSLParameters.) (.setEndpointIdentificationAlgorithm "HTTPS"))
          config (-> (DefaultJedisClientConfig/builder ^URI uri)
                     (.connectionTimeoutMillis 2000) (.socketTimeoutMillis 2000)
                     (.sslParameters tls) .build)
          pool (doto (ConnectionPoolConfig.) (.setMaxTotal 16) (.setMaxIdle 16)
                 (.setMaxWait (Duration/ofSeconds 2)))
          ^RedisClient client (-> (RedisClient/builder)
                                  (.hostAndPort (JedisURIHelper/getHostAndPort uri))
                                  (.clientConfig config) (.poolConfig pool) .build)]
      (try
        (.ping client)
        (->RedisLimiter client prefix max-requests window-ms)
        (catch Exception _
          (.close client)
          ;; Do not expose credentials from a Redis URI through exception data.
          (throw (ex-info "Unable to connect to the configured Redis rate limiter" {})))))))

(defn open-limiter [{:keys [backend record-writes] :as options}]
  (let [base (open-base-limiter options)]
    (if-not record-writes base
      (let [writes (when (:enabled record-writes)
                     (if (= "memory" backend)
                       (rate-limit/memory-limiter record-writes)
                       ;; Share one connection pool, with a separate counter namespace.
                       (assoc base :prefix (str (:prefix options) ":record-write")
                                   :max-requests (:max-requests record-writes) :window-ms (:window-ms record-writes))))]
        (reify
          rate-limit/Limiter
          (admit! [_ key] (rate-limit/admit! base key))
          rate-limit/RequestLimiter
          (admit-request! [_ request]
            (let [limiter (if (rate-limit/record-write? request) writes base)]
              (if limiter
                (rate-limit/admit! limiter (or (:remote-addr request) "unknown"))
                {:allowed? true :retry-after 0})))
          java.io.Closeable
          (close [_] (when (instance? java.io.Closeable base) (.close ^java.io.Closeable base))))))))
