(ns pds.redis-test
  (:require [clojure.test :refer [deftest is]]
            [pds.redis :as redis]
            [pds.rate-limit :as rate-limit]))

(deftest redis-is-optional-and-configurable
  (is (= {:backend "memory" :max-requests 120 :window-ms 60000} (redis/settings {})))
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
