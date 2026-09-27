(ns pds.record-validation-test
  (:require [clojure.test :refer [deftest is]]
            [pds.lexicon-schema-test :as schemas]
            [pds.record-validation :as validation])
  (:import [java.util.concurrent Semaphore]))

(def write {:action :create :collection schemas/id :validate true})
(defn failure [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))
(defn prepare [cache writes] (validation/prepare! {:record-schema-cache cache} writes))

(deftest validation-modes-and-cache-expiry
  (let [calls (atom 0) clock (atom 0)
        cache (assoc (validation/cache (fn [_] (swap! calls inc) (schemas/record-doc {}))) :clock #(deref clock))]
    (is (= {} (prepare cache [(dissoc write :validate)])))
    (is (= {} (prepare cache [(assoc write :validate false)])))
    (is (= {} (prepare cache [(assoc write :action :delete)])))
    (is (zero? @calls))
    (let [catalogs (prepare cache [write write])]
      (is (contains? catalogs schemas/id))
      (is (= 1 @calls))
      (is (= catalogs (prepare cache [(dissoc write :validate)])))
      (is (= catalogs (prepare cache [write])))
      (reset! clock validation/ttl-ms)
      (is (= {} (prepare cache [(dissoc write :validate)])))
      (is (= catalogs (prepare cache [write])))
      (is (= 2 @calls)))
    (is (contains? (prepare cache [(assoc write :collection "app.bsky.feed.post")]) "app.bsky.feed.post"))
    (is (= 2 @calls) "Bundled schemas never fetch a remote replacement")))

(deftest failure-cooldown-and-capacity
  (let [calls (atom 0) clock (atom 0)
        cache (assoc (validation/cache (fn [_] (swap! calls inc) (throw (ex-info "remote secret" {})))) :clock #(deref clock))]
    (dotimes [_ 2] (is (= "InvalidRecord" (failure #(prepare cache [write])))))
    (is (= 1 @calls))
    (is (= {} (prepare cache [(dissoc write :validate)])))
    (reset! clock validation/retry-ms)
    (is (= "InvalidRecord" (failure #(prepare cache [write]))))
    (is (= 2 @calls)))
  (let [calls (atom []) cache (validation/cache (fn [id] (swap! calls conj id) (assoc (schemas/record-doc {}) "id" id)))]
    (with-redefs [validation/capacity 1]
      (is (contains? (prepare cache [write]) schemas/id))
      (is (= "InvalidRecord" (failure #(prepare cache [(assoc write :collection "com.example.other")]))))
      (is (= [schemas/id] @calls))
      (is (contains? (prepare cache [write]) schemas/id))))
  (let [cache (validation/cache (constantly (schemas/record-doc {})))]
    (with-redefs [validation/byte-capacity 1]
      (is (= "InvalidRecord" (failure #(prepare cache [write]))))
      (is (= {} (prepare cache [(dissoc write :validate)]))))))

(deftest concurrent-lookups-and-busy-permits
  (let [entered (promise) release (promise) calls (atom 0)
        cache (validation/cache (fn [_] (swap! calls inc) (deliver entered true)
                                  (when (= :timeout (deref release 5000 :timeout)) (throw (ex-info "timeout" {})))
                                  (schemas/record-doc {})))
        first-request (future (prepare cache [write]))]
    (try
      (is (= true (deref entered 5000 :timeout)))
      (is (= "InvalidRecord" (failure #(prepare cache [write]))))
      (is (= {} (prepare cache [(dissoc write :validate)])))
      (is (= 1 @calls))
      (deliver release true)
      (is (contains? (deref first-request 5000 {}) schemas/id))
      (is (contains? (prepare cache [write]) schemas/id))
      (finally (deliver release true)))
    (let [^Semaphore permits (:permits cache)]
      (.acquire permits 16)
      (try
        (is (= "InvalidRecord" (failure #(prepare cache [write]))))
        (is (contains? (prepare cache [(dissoc write :validate)]) schemas/id))
        (finally (.release permits 16))))))
