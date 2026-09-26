(ns pds.email-outbox-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.email :as email]))

(use-fixtures :each fixture/isolated-database)
(def message {:to "user@example.com" :subject "Confirm" :text "secret token"})
(defn rows [] (with-open [c (db/connection fixture/*ds*)] (db/query c "SELECT *, payload::text AS body FROM email_outbox")))

(deftest durable-retries-and-redaction
  (let [id (db/transact! fixture/*ds* #(email/enqueue! % message))
        seen (atom [])]
    (is (= :retry (email/deliver-one! fixture/*ds* (fn [m] (swap! seen conj (:id m)) (throw (ex-info "secret" {}))))))
    (is (= "pending" (:status (first (rows)))))
    (is (= "delivery-failed" (:last_error (first (rows)))))
    (is (nil? (email/deliver-one! fixture/*ds* (fn [_] (is false "Backoff must delay retry")))))
    (db/transact! fixture/*ds* #(db/execute! % "UPDATE email_outbox SET available_at = now()"))
    (is (= :sent (email/deliver-one! fixture/*ds* #(swap! seen conj (:id %)))))
    (is (= [id id] @seen))
    (is (= "{}" (:body (first (rows)))))
    (is (nil? (email/deliver-one! fixture/*ds* (fn [_] (is false "Already sent")))))))

(deftest permanent-failure-and-rollback
  (try (db/transact! fixture/*ds* (fn [c] (email/enqueue! c message) (throw (ex-info "rollback" {}))))
       (catch Exception _))
  (is (empty? (rows)))
  (db/transact! fixture/*ds* #(email/enqueue! % message))
  (is (= :failed (email/deliver-one! fixture/*ds* (fn [_] (throw (ex-info "unauthorized" {:retryable false :status 401}))))))
  (is (= "failed" (:status (first (rows))))))

(deftest concurrent-delivery-and-expired-lease
  (db/transact! fixture/*ds* #(email/enqueue! % message))
  (let [started (promise) release (promise)
        first-worker (future (email/deliver-one! fixture/*ds* (fn [_] (deliver started true) @release)))]
    (is (= true (deref started 5000 :timeout)))
    (try (is (nil? (email/deliver-one! fixture/*ds* (fn [_] (is false "Double claim")))))
         (finally (deliver release true)))
    (is (= :sent (deref first-worker 5000 :timeout))))
  (db/transact! fixture/*ds* (fn [c]
                             (email/enqueue! c message)
                             (db/execute! c "UPDATE email_outbox SET status = 'sending', lease_until = now() - interval '1 minute' WHERE status = 'pending'")))
  (is (= :sent (email/deliver-one! fixture/*ds* (fn [_])))))

(deftest exhausted-crashed-deliveries-stop
  (db/transact! fixture/*ds* (fn [c]
                             (email/enqueue! c message)
                             (db/execute! c "UPDATE email_outbox SET status = 'sending', attempts = 10,
                                             lease_until = now() - interval '1 minute'")))
  (is (nil? (email/deliver-one! fixture/*ds* (fn [_] (is false "Exhausted job must not send")))))
  (is (= "failed" (:status (first (rows))))))
