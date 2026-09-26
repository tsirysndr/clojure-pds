(ns pds.blob-cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [pds.blob-cleanup :as cleanup]))
(deftest temporary-upload-grace-period-configuration
  (is (= 86400 (:blob-temp-ttl-seconds (cleanup/settings {}))))
  (doseq [value ["3600" "2592000"]]
    (is (= (Long/parseLong value) (:blob-temp-ttl-seconds (cleanup/settings {"PDS_BLOB_TEMP_TTL_SECONDS" value})))))
  (doseq [value [nil "0" "3599" "2592001" "-1" "1.5" "forever"]]
    (is (thrown? Exception (cleanup/settings {"PDS_BLOB_TEMP_TTL_SECONDS" value})))))
