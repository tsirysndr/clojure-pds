(ns pds.redis-integration-test
  (:require [clojure.test :refer [deftest is]]
            [pds.rate-limit :as rate-limit]
            [pds.redis :as redis])
  (:import [java.util UUID]
           [redis.clients.jedis RedisClient]))

(when-let [url (System/getenv "PDS_TEST_REDIS_URL")]
  (deftest shared-windows-and-reconnect
    (let [prefix (str "test-" (UUID/randomUUID))
          config (redis/settings {"PDS_RATE_LIMIT_BACKEND" "redis" "PDS_REDIS_URL" url
                                  "PDS_REDIS_PREFIX" prefix "PDS_RATE_LIMIT_REQUESTS" "10"})]
      (with-open [a (redis/open-limiter config) b (redis/open-limiter config)]
        (let [jobs (mapv (fn [n] (future (rate-limit/admit! (if (even? n) a b) "127.0.0.1"))) (range 50))
              decisions (mapv deref jobs)]
          (is (= 10 (count (filter :allowed? decisions))) "Two clients share one atomic budget")
          (is (every? #(<= 1 (:retry-after %) 60) decisions))
          (is (:allowed? (rate-limit/admit! b "127.0.0.2")))
          (with-open [reopened (redis/open-limiter config)]
            (is (false? (:allowed? (rate-limit/admit! reopened "127.0.0.1")))))
          (let [key (redis/bucket-key prefix "127.0.0.1")]
            (is (= "10" (.get ^RedisClient (:client a) key)))
            (is (pos? (.pttl ^RedisClient (:client a) key)))
            ;; Expire a single namespaced test key using the real Redis clock.
            (.pexpire ^RedisClient (:client a) key 1)
            (Thread/sleep 10)
            (is (:allowed? (rate-limit/admit! b "127.0.0.1"))))
          (with-open [isolated (redis/open-limiter (assoc config :prefix (str prefix "-other")))]
            (is (:allowed? (rate-limit/admit! isolated "127.0.0.1"))))
          (.close ^java.io.Closeable a)
          (is (= 503 (:status ((rate-limit/wrap (constantly {:status 200}) a) {:remote-addr "127.0.0.1"}))))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unable to connect"
                           (redis/open-limiter (assoc config :uri (java.net.URI. "redis://127.0.0.1:1"))))))))
