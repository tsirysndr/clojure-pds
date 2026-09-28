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
          ;; The public limiter wraps derived budgets; open a bare record to
          ;; inspect raw keys with its Redis client.
          (with-open [inspector (redis/open-limiter (dissoc config :proxy-account :record-writes))]
            (let [key (redis/bucket-key prefix "127.0.0.1") ^RedisClient client (:client inspector)]
              (is (= "10" (.get client key)))
              (is (pos? (.pttl client key)))
              ;; Expire a single namespaced test key using the real Redis clock.
              (.pexpire client key 1)
              (Thread/sleep 10)
              (is (:allowed? (rate-limit/admit! b "127.0.0.1")))))
          (with-open [isolated (redis/open-limiter (assoc config :prefix (str prefix "-other")))]
            (is (:allowed? (rate-limit/admit! isolated "127.0.0.1"))))
          (.close ^java.io.Closeable a)
          (is (= 503 (:status ((rate-limit/wrap (constantly {:status 200}) a) {:remote-addr "127.0.0.1"}))))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unable to connect"
                           (redis/open-limiter (assoc config :uri (java.net.URI. "redis://127.0.0.1:1"))))))))

(when-let [url (System/getenv "PDS_TEST_REDIS_URL")]
  (deftest record-write-budgets-are-shared-separate-and-optional
    (let [config (redis/settings {"PDS_RATE_LIMIT_BACKEND" "redis" "PDS_REDIS_URL" url
                                  "PDS_REDIS_PREFIX" (str "test-" (UUID/randomUUID))
                                  "PDS_RATE_LIMIT_REQUESTS" "1" "PDS_RECORD_WRITE_RATE_LIMIT_REQUESTS" "2"})
          request {:remote-addr "127.0.0.1" :request-method :post :uri "/xrpc/com.atproto.repo.putRecord"}]
      (with-open [a (redis/open-limiter config) b (redis/open-limiter config)]
        (is (:allowed? (rate-limit/admit-request! a request)))
        (is (:allowed? (rate-limit/admit-request! b request)))
        (is (false? (:allowed? (rate-limit/admit-request! a request))))
        (is (:allowed? (rate-limit/admit! a "127.0.0.1")))
        (is (false? (:allowed? (rate-limit/admit! b "127.0.0.1"))))
        (with-open [disabled (redis/open-limiter (assoc-in config [:record-writes :enabled] false))]
          (.close ^java.io.Closeable disabled)
          (is (= 200 (:status ((rate-limit/wrap (constantly {:status 200}) disabled) request))))
          (is (= 503 (:status ((rate-limit/wrap (constantly {:status 200}) disabled) (assoc request :uri "/"))))))))))

(when-let [url (System/getenv "PDS_TEST_REDIS_URL")]
  (deftest per-account-proxy-budgets-are-shared-and-namespaced
    (let [config (redis/settings {"PDS_RATE_LIMIT_BACKEND" "redis" "PDS_REDIS_URL" url
                                  "PDS_REDIS_PREFIX" (str "test-" (UUID/randomUUID))
                                  "PDS_RATE_LIMIT_REQUESTS" "100"
                                  "PDS_PROXY_ACCOUNT_RATE_LIMIT_REQUESTS" "2"
                                  "PDS_PROXY_ACCOUNT_RATE_LIMIT_WINDOW_SECONDS" "60"})
          alice "did:web:alice.example.com"]
      (with-open [a (redis/open-limiter config) b (redis/open-limiter config)]
        (is (:allowed? (rate-limit/admit-account! a "proxy" alice)))
        (is (:allowed? (rate-limit/admit-account! b "proxy" alice)))
        (is (false? (:allowed? (rate-limit/admit-account! a "proxy" alice)))
            "Two processes share one per-account budget")
        (is (:allowed? (rate-limit/admit-account! b "proxy" "did:web:bob.example.com")))
        (is (:allowed? (rate-limit/admit! a alice))
            "The account namespace is separate from the general budget")
        (with-open [disabled (redis/open-limiter (assoc-in config [:proxy-account :enabled] false))]
          (dotimes [_ 3]
            (is (:allowed? (rate-limit/admit-account! disabled "proxy" alice)))))))))
