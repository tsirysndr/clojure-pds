(ns ci.sqlite
  (:require [ci.common :as common]
            [dagger.container :as ctr]))

(defn ^{:dagger/return :string} sqlite
  "Run the explicitly supported SQLite integration namespaces."
  [^:dagger/context {:keys [client]} ^{:dagger/type [:object "Directory"]} source]
  (-> (common/runner client source)
      (ctr/with-env-variable "PDS_TEST_DATABASE_URL" "jdbc:sqlite:")
      (ctr/with-exec ["clojure" "-Sdeps" "{:aliases {:sqlite-tests {:extra-paths [\"test\" \"test-integration\"]}}}"
                      "-M:sqlite-tests" "-m" "pds.sqlite-test-runner"])
      common/finish))
