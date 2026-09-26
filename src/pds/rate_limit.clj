(ns pds.rate-limit
  (:require [pds.xrpc :as xrpc]))

(defn wrap
  "Bounded per-process IP rate limits. Reverse proxies must preserve fair limits
  externally; untrusted X-Forwarded-For is deliberately not used."
  [handler]
  (let [buckets (atom {})]
    (fn [request]
      (let [now (System/currentTimeMillis)
            interval 60000
            key (or (:remote-addr request) "unknown")
            admitted? (volatile! false)]
        (swap! buckets
               (fn [current]
                 (let [current (into {} (filter (fn [[_ v]] (< now (:until v)))) current)]
                   current)))
        ;; All decisions happen in one atomic update; reset the flag on retries.
        (swap! buckets
               (fn [current]
                 (vreset! admitted? false)
                 (let [bucket (get current key {:count 0 :until (+ now interval)})]
                   (if (or (>= (:count bucket) 120)
                           (and (not (contains? current key)) (>= (count current) 10000)))
                     current
                     (do (vreset! admitted? true) (assoc current key (update bucket :count inc)))))))
        (if @admitted? (handler request)
            (assoc-in (xrpc/error-response 429 "RateLimitExceeded" "Too many requests") [:headers "Retry-After"] "60"))))))
