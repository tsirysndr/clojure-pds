(ns pds.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [pds.config :as config]))

(deftest environment-configuration
  (is (= {:host "127.0.0.1" :port 3000 :hostname "localhost"
          :service-did "did:web:localhost"}
         (config/load-config {})))
  (is (= {:host "0.0.0.0" :port 8080 :hostname "pds.example.com"
          :service-did "did:web:pds.example.com"}
         (config/load-config {"PDS_HOST" "0.0.0.0" "PDS_PORT" "8080"
                              "PDS_HOSTNAME" "pds.example.com"})))
  (doseq [p ["0" "65535"]]
    (is (= (Long/parseLong p) (:port (config/load-config {"PDS_PORT" p}))))))

(deftest invalid-configuration-fails-before-startup
  (doseq [[variable values]
          {"PDS_PORT" ["" "-1" "65536" "999999999999999999" "3.5" "3000 " "abc"]
           "PDS_HOST" ["" " " "127.0.0.1\n"]
           "PDS_HOSTNAME" ["" "https://example.com" "example.com:3000"
                           "Example.com" "-bad.com" "bad-.com" "a..com"
                           "example.com/" "example.com." "a_b.com"
                           (str (apply str (repeat 64 "a")) ".com")]}]
    (doseq [value values]
      (testing (str variable " rejects " (pr-str value))
        (try
          (config/load-config {variable value})
          (is false "Expected a configuration error")
          (catch clojure.lang.ExceptionInfo e
            (is (= variable (:variable (ex-data e))))))))))
