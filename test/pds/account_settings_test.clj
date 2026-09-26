(ns pds.account-settings-test
  (:require [clojure.test :refer [deftest is]]
            [pds.accounts :as accounts]))

(deftest portable-identity-configuration
  (is (= :web (:did-method (accounts/settings {}))))
  (is (= :plc (:did-method (accounts/settings {"PDS_DID_METHOD" "plc" "PDS_USER_DOMAIN" "example.com"
                                               "PDS_PUBLIC_URL" "https://pds.example.com"}))))
  (doseq [env [{"PDS_DID_METHOD" "unknown"} {"PDS_DID_METHOD" "plc"}
              {"PDS_DID_METHOD" "plc" "PDS_PUBLIC_URL" "https://pds.example.com" "PDS_USER_DOMAIN" "pds.localhost"}
              {"PDS_DID_METHOD" "plc" "PDS_PUBLIC_URL" "http://localhost:3000" "PDS_USER_DOMAIN" "example.com"}]]
    (is (thrown? Exception (accounts/settings env)))))
