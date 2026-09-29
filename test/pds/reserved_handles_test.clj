(ns pds.reserved-handles-test
  (:require [clojure.test :refer [deftest is]]
            [pds.reserved-handles :as reserved]))

(deftest environment-selects-defaults-overrides-or-nothing
  (is (= reserved/default (:reserved-handles (reserved/settings {}))))
  (is (contains? reserved/default "admin"))
  (is (= #{} (:reserved-handles (reserved/settings {"PDS_RESERVED_HANDLES" ""})))
      "An empty value disables reservation")
  (is (= #{} (:reserved-handles (reserved/settings {"PDS_RESERVED_HANDLES" "   "}))))
  (is (= #{"www" "admin"}
         (:reserved-handles (reserved/settings {"PDS_RESERVED_HANDLES" " WWW , admin ,www"})))
      "Labels are trimmed, lowercased and deduplicated")
  (doseq [value ["bad label" "-leading" "trailing-" "a.b" "www,,admin" "under_score"
                 (apply str (repeat 64 "a"))]]
    (is (thrown? clojure.lang.ExceptionInfo (reserved/settings {"PDS_RESERVED_HANDLES" value}))
        (str "rejects " (pr-str value)))))

(deftest reserved-first-labels-block-self-service-claims
  (let [settings (reserved/settings {"PDS_RESERVED_HANDLES" "www,admin"})]
    (is (reserved/blocked? settings "www.example.com"))
    (is (reserved/blocked? settings "ADMIN.example.com") "Comparison is case insensitive")
    (is (not (reserved/blocked? settings "alice.example.com")))
    (is (not (reserved/blocked? settings "administrator.example.com"))
        "Only whole labels are reserved, not prefixes")
    (is (not (reserved/blocked? settings "alice.www.example.com"))
        "Only the first label is reserved")
    (is (not (reserved/blocked? settings "www.example.com" "www.example.com"))
        "An account already holding a reserved handle keeps it")
    (is (reserved/blocked? settings "admin.example.com" "www.example.com"))
    (is (not (reserved/blocked? {} "www.example.com"))
        "Settings without a list reserve nothing")))
