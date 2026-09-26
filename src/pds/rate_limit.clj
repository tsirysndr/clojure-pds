(ns pds.rate-limit
  (:require [pds.xrpc :as xrpc]))

(defprotocol Limiter
  (admit! [limiter key] "Return {:allowed? boolean :retry-after seconds}."))

(defn memory-limiter
  "Bounded process-local fixed windows; the first request starts the window."
  ([] (memory-limiter {:max-requests 120 :window-ms 60000}))
  ([{:keys [max-requests window-ms] :as options}]
   (let [buckets (atom {}) clock (get options :clock #(System/currentTimeMillis))]
     (reify Limiter
       (admit! [_ key]
         (let [now (clock) admitted? (volatile! false) remaining (volatile! window-ms)]
           ;; Prune and decide in one atomic update. Reset flags on CAS retries.
           (swap! buckets
                  (fn [current]
                    (vreset! admitted? false)
                    (let [current (into {} (filter (fn [[_ v]] (< now (:until v)))) current)
                          bucket (get current key {:count 0 :until (+ now window-ms)})]
                      (vreset! remaining (- (:until bucket) now))
                      (if (or (>= (:count bucket) max-requests)
                              (and (not (contains? current key)) (>= (count current) 10000)))
                        current
                        (do (vreset! admitted? true) (assoc current key (update bucket :count inc)))))))
           {:allowed? @admitted? :retry-after (max 1 (long (Math/ceil (/ @remaining 1000.0))))}))))))

(defn wrap
  "Untrusted forwarding headers are deliberately ignored. A configured shared
  limiter outage fails closed; it never silently resets to local counters."
  ([handler] (wrap handler (memory-limiter)))
  ([handler limiter]
   (fn [request]
     (let [decision (try (admit! limiter (or (:remote-addr request) "unknown"))
                         (catch Exception _ nil))]
       (cond
         (nil? decision) (assoc-in (xrpc/error-response 503 "RateLimitUnavailable" "Rate limiting is unavailable")
                                  [:headers "Retry-After"] "1")
         (:allowed? decision) (handler request)
         :else (assoc-in (xrpc/error-response 429 "RateLimitExceeded" "Too many requests")
                         [:headers "Retry-After"] (str (:retry-after decision))))))))
