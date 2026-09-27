(ns pds.db-pool-settings-test
  (:require [clojure.test :refer [deftest is]]
            [pds.db :as db]))

(deftest bounded-pool-configuration
  (is (= {:maximum-size 20 :timeout-ms 5000} (db/pool-settings {})))
  (is (= {:maximum-size 1 :timeout-ms 500}
         (db/pool-settings {"PDS_DB_POOL_SIZE" "1" "PDS_DB_POOL_TIMEOUT_MS" "500"})))
  (is (= {:maximum-size 256 :timeout-ms 60000}
         (db/pool-settings {"PDS_DB_POOL_SIZE" "256" "PDS_DB_POOL_TIMEOUT_MS" "60000"})))
  (doseq [[key values] [["PDS_DB_POOL_SIZE" ["0" "257" "-1" "1.5" "" "secret"]]
                        ["PDS_DB_POOL_TIMEOUT_MS" ["0" "499" "60001" "" "9223372036854775808"]]]
          value values]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be between"
                         (db/pool-settings {key value})))))
