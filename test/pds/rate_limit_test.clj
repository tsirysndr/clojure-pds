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

(deftest fixed-window-expiry-and-concurrency
  (let [clock (atom 1000)
        limiter (rate-limit/memory-limiter {:max-requests 10 :window-ms 1000 :clock #(deref clock)})
        jobs (mapv (fn [_] (future (rate-limit/admit! limiter "one-ip"))) (range 40))]
    (is (= 10 (count (filter :allowed? (mapv deref jobs)))))
    (swap! clock + 999)
    (is (false? (:allowed? (rate-limit/admit! limiter "one-ip"))))
    (swap! clock inc)
    (is (:allowed? (rate-limit/admit! limiter "one-ip")))))

(deftest limiter-outage-is-sanitized
  (let [calls (atom 0)
        limiter (reify rate-limit/Limiter (admit! [_ _] (throw (ex-info "redis://user:secret@host" {}))))
        response ((rate-limit/wrap (fn [_] (swap! calls inc) {:status 200}) limiter) {})]
    (is (= 503 (:status response)))
    (is (= 0 @calls))
    (is (not (.contains (:body response) "secret")))))
