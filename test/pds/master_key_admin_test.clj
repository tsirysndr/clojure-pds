(ns pds.master-key-admin-test
  (:require [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.master-key-admin :as cli]
            [pds.master-keys :as keys]))

(deftest cli-validates-input-and-sanitizes-errors
  (doseq [args [[] ["rewrap"] ["rewrap" "invalid"] ["register" "extra"] ["unknown"]]]
    (is (= 64 (:exit (cli/run! args {})))))
  (let [key (crypto/random-bytes 32) fingerprint (keys/fingerprint key)]
    (is (= 43 (count fingerprint)))
    (is (not= fingerprint (crypto/b64 key)))
    (doseq [value [nil "" "secret-invalid-key" (crypto/b64 (byte-array 31))]]
      (let [result (cli/run! ["rewrap" fingerprint] {"PDS_MASTER_KEY" value})]
        (is (= "InvalidKey" (get-in result [:result :error])))
        (is (not (.contains (pr-str result) "secret-invalid-key")))))
    (let [result (cli/run! ["status"] {"PDS_DATABASE_URL" "secret-invalid-url"})]
      (is (= "MasterKeyOperationFailed" (get-in result [:result :error])))
      (is (not (.contains (pr-str result) "secret-invalid-url"))))))
