(ns pds.oauth.server-test
  (:require [clojure.test :refer [deftest is]]
            [pds.oauth.server :as server]))

(deftest canonical-origin-is-required-before-discovery-is-published
  (doseq [url ["https://pds.example.com" "https://pds.example.com:8443" "http://localhost:3000" "http://127.0.0.1:3000"]]
    (is (= {:public-url url} (server/validate-origin! {:public-url url}))))
  (doseq [url [nil "" "https://PDS.example.com" "http://pds.example.com" "https://pds.example.com:443"
               "http://localhost:80" "https://pds.example.com/" "https://pds.example.com/path"
               "https://pds.example.com?x=1" "https://pds.example.com#fragment" "https://user@pds.example.com"
               "https://pds.example.com:0" "https://pds.example.com:65536"]]
    (is (thrown? clojure.lang.ExceptionInfo (server/validate-origin! {:public-url url})))))

(deftest database-free-handler-does-not-advertise-oauth
  (let [handler (constantly {:status 404})]
    (is (identical? handler (server/wrap handler nil {} nil)))
    (is (identical? handler (server/wrap-headers handler nil {})))))
