(ns pds.security.passkeys-test
  (:require [clojure.test :refer [deftest is]]
            [pds.security.passkeys :as passkeys]))

(deftest configured-origin-is-the-only-rp-authority
  (is (= {:origin "https://pds.example.com" :rp-id "pds.example.com"}
         (passkeys/rp-origin {:public-url "https://pds.example.com" :hostname "unrelated.example.com"})))
  (is (= {:origin "http://localhost:3000" :rp-id "localhost"}
         (passkeys/rp-origin {:public-url "http://localhost:3000"})))
  (doseq [url [nil "http://pds.example.com" "https://pds.example.com/" "https://pds.example.com?other"
               "https://user@pds.example.com" "https://pds.example.com#other" "https://pds.example.com/path"]]
    (is (thrown? Exception (passkeys/rp-origin {:public-url url})))))
