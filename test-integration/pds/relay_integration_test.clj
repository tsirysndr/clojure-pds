(ns pds.relay-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.relay :as relay]))

(use-fixtures :each fixture/isolated-database)
(def config (assoc (relay/settings {"PDS_RELAY_URLS" "https://relay.example.com"}) :public-url "https://pds.example.com"))
(defn rows [] (with-open [conn (db/connection fixture/*ds*)] (db/query conn "SELECT *, extract(epoch FROM next_attempt_at - now()) AS delay FROM relay_announcements ORDER BY relay_url")))
(defn due! [] (db/transact! fixture/*ds* #(db/execute! % "UPDATE relay_announcements SET next_attempt_at = now() - interval '1 second'")))

(deftest durable-schedule-success-retry-and-configuration-filter
  (let [calls (atom 0) response (atom {:status 503 :headers {"retry-after" "120"}})
        settings (assoc config :relay-post (fn [_ _ _] (swap! calls inc) @response))]
    (relay/seed! fixture/*ds* settings)
    (is (= :retry (relay/process-one! fixture/*ds* settings)))
    (let [row (first (rows))]
      (is (= 1 (:attempts row)))
      (is (= 503 (:last_status row)))
      (is (= "http-error" (:last_error row)))
      (is (<= 115 (:delay row) 120))
      (is (nil? (:lease_id row))))
    (relay/seed! fixture/*ds* settings)
    (is (nil? (relay/process-one! fixture/*ds* settings)))
    (is (= 1 @calls) "A restarted instance does not reset the schedule")
    (due!) (reset! response {:status 200})
    (is (= :announced (relay/process-one! fixture/*ds* settings)))
    (let [row (first (rows))]
      (is (zero? (:attempts row)))
      (is (some? (:last_success_at row)))
      (is (nil? (:last_error row)))
      (is (<= 1195 (:delay row) 1200)))
    (due!)
    (is (nil? (relay/process-one! fixture/*ds* (assoc settings :relay-urls []))))
    (is (nil? (relay/process-one! fixture/*ds* (assoc settings :public-url "https://other.example.com"))))
    (is (= 2 @calls))))

(deftest failures-back-off-without-remote-error-details
  (let [settings (assoc config :relay-post (fn [& _] (throw (ex-info "remote response contains secret" {}))))]
    (relay/seed! fixture/*ds* settings)
    (doseq [expected [5 10 20]]
      (due!)
      (is (= :retry (relay/process-one! fixture/*ds* settings)))
      (let [row (first (rows))]
        (is (<= (- expected 3) (:delay row) expected))
        (is (= "transport-error" (:last_error row)))
        (is (nil? (:last_status row)))))
    (db/transact! fixture/*ds* #(db/execute! % "UPDATE relay_announcements SET attempts = 30"))
    (due!) (relay/process-one! fixture/*ds* settings)
    (is (= 30 (:attempts (first (rows)))))
    (is (<= 3595 (:delay (first (rows))) 3600))))

(deftest leases-allow-other-workers-and-fence-late-completions
  (let [entered (promise) release (promise) ds fixture/*ds*
        settings (assoc config :relay-post (fn [& _] (deliver entered true)
                                            (deref release 5000 :timeout) {:status 503}))]
    (relay/seed! ds settings)
    (let [worker (future (relay/process-one! ds settings))]
      (try
        (is (= true (deref entered 5000 :timeout)))
        (is (nil? (relay/process-one! ds settings)) "A live lease prevents a second request")
        ;; No row/account/global locks span delivery. Simulate a crashed/paused
        ;; worker whose lease expires, then let another process succeed.
        (db/transact! ds #(do (db/execute! % "SET LOCAL lock_timeout = '500ms'")
                              (db/execute! % "UPDATE relay_announcements SET lease_until = now() - interval '1 second'")))
        (is (= :announced (relay/process-one! ds (assoc settings :relay-post (fn [& _] {:status 200})))))
        (deliver release true)
        (is (nil? (deref worker 5000 :timeout)))
        (is (= 200 (:last_status (first (rows)))))
        (is (= 0 (:attempts (first (rows)))))
        (finally (deliver release true))))))

(deftest independent-relays-and-worker-lifecycle
  (let [settings (assoc config :relay-urls ["https://one.example.com" "https://two.example.com"])
        completed (promise) process relay/process-one!
        settings (assoc settings :relay-post (fn [& _] {:status 200}))]
    (with-redefs [relay/process-one! (fn [ds settings]
                                     (let [result (process ds settings)] (deliver completed result) result))]
      (let [stop! (relay/start! fixture/*ds* settings)]
        (try
          (is (= :announced (deref completed 5000 :timeout)))
          (finally (stop!)))))
    (is (= 2 (count (rows))))
    (is (contains? #{nil :announced} (relay/process-one! fixture/*ds* settings)))
    (is (every? :last_success_at (rows)))
    (is (nil? (relay/process-one! fixture/*ds* settings)))))
