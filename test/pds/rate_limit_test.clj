(ns pds.rate-limit-test
  (:require [clojure.test :refer [deftest is]]
            [pds.rate-limit :as rate-limit]))
(deftest untrusted-forwarding-cannot-bypass-limits
  (let [handler (rate-limit/wrap (fn [_] {:status 200}))
        request {:remote-addr "127.0.0.1"}]
    (dotimes [_ 120] (is (= 200 (:status (handler request)))))
    (let [response (handler (assoc request :headers {"x-forwarded-for" "1.2.3.4"}))]
      (is (= 429 (:status response)))
      (is (= "60" (get-in response [:headers "Retry-After"]))))
    (is (= 200 (:status (handler {:remote-addr "127.0.0.2"}))))))
