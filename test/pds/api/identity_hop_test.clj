(ns pds.api.identity-hop-test
  (:require [clojure.test :refer [deftest is testing]]
            [pds.api.identity :as identity-api]))

(deftest a-delegate-hop-is-recognised-by-either-name
  (let [hop? #'identity-api/delegate-hop?]
    ;; A hop must never resolve outward: in a shared namespace that loops back
    ;; through the gateway's TLS ask to this server.
    (is (true? (hop? {:headers {"x-pdsgw-delegate-hop" "1"}})))
    (is (true? (hop? {:headers {"atoll-delegate-hop" "1"}})))
    (is (false? (hop? {:headers {}})))
    (is (false? (hop? {:headers {"x-forwarded-for" "203.0.113.7"}})))))
