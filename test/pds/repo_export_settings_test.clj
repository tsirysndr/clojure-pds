(ns pds.repo-export-settings-test
  (:require [clojure.test :refer [deftest is]]
            [pds.repo-export :as export]))

(deftest export-size-configuration
  (is (= (* 256 1024 1024) (:repo-export-max-bytes (export/settings {}))))
  (doseq [n [1048576 17179869184]]
    (is (= n (:repo-export-max-bytes (export/settings {"PDS_REPO_EXPORT_MAX_BYTES" (str n)})))))
  (doseq [value ["0" "1048575" "17179869185" "-1" "a" "999999999999999999999" ""]]
    (is (thrown? clojure.lang.ExceptionInfo (export/settings {"PDS_REPO_EXPORT_MAX_BYTES" value})))))
