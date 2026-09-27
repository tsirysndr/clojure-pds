(ns pds.redis-test
  (:require [clojure.test :refer [deftest is]]
            [pds.redis :as redis]
            [pds.rate-limit :as rate-limit]))

(deftest redis-is-optional-and-configurable
  (is (= {:backend "memory" :max-requests 120 :window-ms 60000
          :proxy-account {:enabled true :max-requests 600 :window-ms 300000}} (redis/settings {})))
  (is (= {:enabled false :max-requests 30 :window-ms 60000}
         (:proxy-account (redis/settings {"PDS_PROXY_ACCOUNT_RATE_LIMIT_ENABLED" "false"
                                          "PDS_PROXY_ACCOUNT_RATE_LIMIT_REQUESTS" "30"
                                          "PDS_PROXY_ACCOUNT_RATE_LIMIT_WINDOW_SECONDS" "60"}))))
  (is (thrown? clojure.lang.ExceptionInfo (redis/settings {"PDS_PROXY_ACCOUNT_RATE_LIMIT_ENABLED" "maybe"})))
  (is (thrown? clojure.lang.ExceptionInfo (redis/settings {"PDS_PROXY_ACCOUNT_RATE_LIMIT_REQUESTS" "0"})))
  (let [limiter (redis/open-limiter (redis/settings {}))]
    (is (satisfies? rate-limit/AccountLimiter limiter))
    (is (true? (:allowed? (rate-limit/admit-account! limiter "proxy" "did:web:alice.example.com")))))
  (let [limiter (redis/open-limiter (redis/settings {"PDS_PROXY_ACCOUNT_RATE_LIMIT_ENABLED" "false"}))]
    (dotimes [_ 3]
      (is (true? (:allowed? (rate-limit/admit-account! limiter "proxy" "did:web:alice.example.com")))
          "A disabled per-account budget admits unconditionally")))
  (is (satisfies? rate-limit/Limiter (redis/open-limiter (redis/settings {}))))
  (let [env {"PDS_RATE_LIMIT_BACKEND" "redis" "PDS_REDIS_URL" "rediss://user:password@redis.example.com:6380/2"}]
    (is (= "rediss" (.getScheme (:uri (redis/settings env)))))
    (is (= "clojure-pds" (:prefix (redis/settings env))))
    (doseq [bad ["" "http://localhost:6379" "redis:///2" "redis://localhost:0" "redis://localhost/not-a-db"
                 "redis://localhost/0?password=secret" "redis://localhost/#fragment"]]
      (is (thrown? clojure.lang.ExceptionInfo (redis/settings (assoc env "PDS_REDIS_URL" bad)))))
    (doseq [[key value] [["PDS_RATE_LIMIT_BACKEND" "unknown"] ["PDS_REDIS_PREFIX" "space prefix"]
                         ["PDS_RATE_LIMIT_REQUESTS" "0"] ["PDS_RATE_LIMIT_REQUESTS" "no"]
                         ["PDS_RATE_LIMIT_WINDOW_SECONDS" "3601"]]]
      (is (thrown? clojure.lang.ExceptionInfo (redis/settings (assoc env key value)))))))

(deftest configurable-record-write-budget-and-disable-switch
  (let [config (redis/settings {"PDS_RECORD_WRITE_RATE_LIMIT_REQUESTS" "2" "PDS_RECORD_WRITE_RATE_LIMIT_WINDOW_SECONDS" "300"
                                "PDS_RATE_LIMIT_REQUESTS" "1"})
        limiter (redis/open-limiter config)
        handler (rate-limit/wrap (constantly {:status 200}) limiter)
        request {:remote-addr "127.0.0.1" :request-method :post :uri "/xrpc/com.atproto.repo.createRecord"}]
    (is (= {:enabled true :max-requests 2 :window-ms 300000} (:record-writes config)))
    (is (= 200 (:status (handler request))))
    (is (= 200 (:status (handler (assoc request :uri "/xrpc/com.atproto.repo.applyWrites")))) )
    (let [blocked (handler request)]
      (is (= 429 (:status blocked)))
      (is (= "300" (get-in blocked [:headers "Retry-After"]))))
    (is (= 200 (:status (handler (assoc request :uri "/xrpc/com.atproto.server.createSession")))) )
    (is (= 429 (:status (handler (assoc request :uri "/xrpc/com.atproto.server.createSession"))))))
  (let [limiter (redis/open-limiter (redis/settings {"PDS_RECORD_WRITE_RATE_LIMIT_ENABLED" "false" "PDS_RATE_LIMIT_REQUESTS" "1"}))
        handler (rate-limit/wrap (constantly {:status 200}) limiter)
        request {:remote-addr "127.0.0.1" :request-method :post}]
    (is (= 200 (:status (handler request))))
    (is (= 429 (:status (handler request))))
    (doseq [method ["createRecord" "putRecord" "deleteRecord" "applyWrites"]]
      (dotimes [_ 3]
        (is (= 200 (:status (handler (assoc request :uri (str "/xrpc/com.atproto.repo." method))))))))
    (is (= 429 (:status (handler (assoc request :uri "/xrpc/com.atproto.repo.uploadBlob")))))
    (is (= 429 (:status (handler (assoc request :uri "/xrpc/com.atproto.repo.createRecord" :request-method :get))))))
  (doseq [[key value] [["PDS_RECORD_WRITE_RATE_LIMIT_ENABLED" "yes"] ["PDS_RECORD_WRITE_RATE_LIMIT_ENABLED" "0"]
                       ["PDS_RECORD_WRITE_RATE_LIMIT_REQUESTS" "0"] ["PDS_RECORD_WRITE_RATE_LIMIT_REQUESTS" "1000001"]
                       ["PDS_RECORD_WRITE_RATE_LIMIT_WINDOW_SECONDS" "-1"] ["PDS_RECORD_WRITE_RATE_LIMIT_WINDOW_SECONDS" "86401"]]]
    (is (thrown? clojure.lang.ExceptionInfo (redis/settings {key value})))))
