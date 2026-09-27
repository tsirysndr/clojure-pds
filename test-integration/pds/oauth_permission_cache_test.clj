(ns pds.oauth-permission-cache-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.lexicon-resolver-test :as publisher]
            [pds.oauth.permission-cache :as cache]
            [pds.oauth.permission-sets-test :as sets]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax]))

(use-fixtures :each fixture/isolated-database)
(defn resolved [nsid version]
  (let [schema (-> (sets/schema [(assoc sets/repo "action" (if (= 1 version) ["create"] ["delete"]))])
                   (assoc "id" nsid))]
    {:nsid nsid :schema schema :did "did:web:publisher.example.com"
     :cid (codec/cid (codec/encode schema)) :head (codec/cid (codec/encode {"version" version}))
     :rev (syntax/encode-tid (* version 1024))}))
(defn env []
  (let [clock (atom 1800000000) calls (atom []) value (atom 1)]
    {:clock clock :calls calls :value value
     :cache {:ds fixture/*ds* :clock #(deref clock)
             :resolve (fn [nsid]
                        (swap! calls conj nsid)
                        (if (= :fail @value) (throw (ex-info "private remote payload" {}))
                          (resolved nsid @value)))}}))
(defn unavailable? [f]
  (try (f) false (catch clojure.lang.ExceptionInfo e (= "temporarily_unavailable" (:oauth-error (ex-data e))))))
(defn rows [] (with-open [conn (db/connection fixture/*ds*)] (db/query conn "SELECT * FROM oauth_permission_set_cache")))

(deftest fresh-cache-survives-reopen-and-stale-refresh-replaces-it
  (let [{:keys [cache clock calls value]} (env)
        first (cache/resolve! cache sets/nsid)
        reopened (assoc cache :resolve (fn [_] (throw (AssertionError. "Fresh cache must not resolve"))))]
    (is (= 1 (count @calls)))
    (is (= first (cache/resolve! reopened sets/nsid)))
    (swap! clock + (dec cache/stale-seconds))
    (is (= first (cache/resolve! reopened sets/nsid)))
    (reset! value 2) (swap! clock inc)
    (let [next (cache/resolve! cache sets/nsid)]
      (is (= 2 (count @calls)))
      (is (not= (:cid first) (:cid next)))
      (is (= @clock (:fetched-at next)))
      (is (= next (cache/resolve! reopened sets/nsid))))))

(deftest failed-refresh-preserves-age-and-session-fallback-outlives-expiry
  (let [{:keys [cache clock calls value]} (env) initial (cache/resolve! cache sets/nsid)]
    (reset! value :fail) (swap! clock + cache/stale-seconds)
    (is (= initial (cache/resolve! cache sets/nsid)))
    (is (= initial (cache/resolve! cache sets/nsid)))
    (is (= 2 (count @calls)) "Retry cooldown suppresses request storms")
    (reset! clock (+ (:fetched-at initial) cache/expiry-seconds))
    (is (unavailable? #(cache/resolve! cache sets/nsid)))
    (is (= initial (cache/resolve! cache sets/nsid initial)))
    (is (= (:fetched-at initial) (:fetched_at (first (rows)))))
    (db/transact! fixture/*ds* #(db/execute! % "DELETE FROM oauth_permission_set_cache"))
    (is (= initial (cache/resolve! cache sets/nsid initial)) "Existing session survives cache eviction during outage")
    (is (unavailable? #(cache/resolve! cache sets/nsid)) "A session fallback must not seed new grants")
    (reset! value 2) (swap! clock + cache/retry-seconds)
    (is (= (:cid (resolved sets/nsid 2)) (:cid (cache/resolve! cache sets/nsid))))))

(deftest expiry-is-rechecked-after-network-failure
  (let [{:keys [cache clock]} (env) initial (cache/resolve! cache sets/nsid)]
    (reset! clock (dec (+ (:fetched-at initial) cache/expiry-seconds)))
    (is (unavailable? #(cache/resolve! (assoc cache :resolve (fn [_] (swap! clock + 2) (throw (Exception.)))) sets/nsid)))))

(deftest only-one-process-refreshes-and-no-network-call-holds-the-cache-lock
  (let [{:keys [cache clock]} (env) initial (cache/resolve! cache sets/nsid)
        entered (promise) release (promise) calls (atom 0)
        other (assoc cache :resolve (fn [nsid]
                                     (swap! calls inc)
                                     (db/transact! fixture/*ds*
                                       (fn [conn]
                                         (is (= true (:locked (first (db/query conn "SELECT pg_try_advisory_xact_lock(731946282) AS locked")))))))
                                     (deliver entered true) @release (resolved nsid 2)))]
    (swap! clock + cache/stale-seconds)
    (let [leader (future (cache/resolve! other sets/nsid))]
      (try
        (is (= true (deref entered 5000 :timeout)))
        (let [followers (mapv (fn [_] (future (cache/resolve! other sets/nsid))) (range 4))]
          (is (every? #(= initial (deref % 5000 :timeout)) followers)))
        (is (= 1 @calls))
        (finally (deliver release true)))
      (is (= (:cid (resolved sets/nsid 2)) (:cid (deref leader 5000 {})))))))

(deftest cold-leases-expire-and-obsolete-workers-cannot-publish
  (let [{:keys [cache clock]} (env) entered (promise) release (promise)
        slow (assoc cache :resolve (fn [nsid] (deliver entered true) @release (resolved nsid 1)))
        leader (future (cache/resolve! slow sets/nsid))]
    (try
      (is (= true (deref entered 5000 :timeout)))
      (is (unavailable? #(cache/resolve! cache sets/nsid)))
      (swap! clock + (inc cache/lease-seconds))
      (let [new (cache/resolve! (assoc cache :resolve #(resolved % 2)) sets/nsid)]
        (deliver release true)
        (is (= new (deref leader 5000 :timeout)))
        (is (= new (cache/resolve! cache sets/nsid))))
      (finally (deliver release true) (deref leader 5000 nil)))))

(deftest rollback-fork-and-invalid-schema-cannot-poison-cache
  (let [{:keys [cache clock value]} (env)]
    (reset! value 2)
    (let [initial (cache/resolve! cache sets/nsid)]
      (doseq [resolve [#(resolved % 1)
                       #(assoc (resolved % 2) :head (:head (resolved % 3)))
                       #(assoc-in (resolved % 3) [:schema "defs" "main" "type"] "record")]]
        (swap! clock + cache/stale-seconds)
        (is (= initial (cache/resolve! (assoc cache :resolve resolve) sets/nsid))))
      (swap! clock + cache/stale-seconds)
      (let [moved (cache/resolve! (assoc cache :resolve #(assoc (resolved % 1) :did "did:web:new-publisher.example.com")) sets/nsid)]
        (is (= "did:web:new-publisher.example.com" (:did moved)) "DNS authority can migrate to another DID")))))

(deftest bounded-cache-admission-retention-and-session-fallback
  (with-redefs [cache/capacity 2]
    (let [{:keys [cache clock]} (env)
          a (cache/resolve! cache sets/nsid)
          b (cache/resolve! cache "com.example.feed.authOther")
          missing "com.example.feed.authThird"
          fallback (assoc (resolved missing 1) :fetched-at @clock)]
      (is (unavailable? #(cache/resolve! cache missing)))
      (is (= fallback (cache/resolve! cache missing fallback)))
      (is (= 2 (count (rows))))
      ;; Entries remain protected while fresh (24h), stronger than the 30m floor.
      (swap! clock + cache/stale-seconds)
      (is (= missing (:nsid (cache/resolve! cache missing))))
      (is (= 2 (count (rows))))
      (is (some #{(:cid a) (:cid b)} (map :cid (rows)))))))

(deftest cached-older-revision-cannot-replace-an-existing-session-snapshot
  (let [{:keys [cache clock]} (env)
        initial (cache/resolve! cache sets/nsid)
        newer (assoc (resolved sets/nsid 2) :fetched-at @clock)]
    (is (= newer (cache/resolve! cache sets/nsid newer)))
    (swap! clock + cache/stale-seconds)
    (is (= newer (cache/resolve! cache sets/nsid newer)))
    (is (= (:rev initial) (:rev (first (rows)))) "Rejected rollback did not publish")))

(deftest verified-signed-lexicon-survives-persistence-and-failed-reverification
  (let [{:keys [resolver response]} (publisher/fixture)
        timestamp (atom 1800000000)
        cache (assoc (cache/cache fixture/*ds* resolver) :clock #(deref timestamp))
        initial (cache/resolve! cache publisher/nsid)]
    (is (= publisher/schema (:schema initial)))
    (reset! response {:status 200 :body (byte-array [1 2 3])})
    (is (= initial (cache/resolve! cache publisher/nsid)) "Fresh cache needs no remote proof")
    (swap! timestamp + cache/stale-seconds)
    (is (= initial (cache/resolve! cache publisher/nsid)) "Invalid remote proof cannot replace the authenticated schema")
    (is (= (:cid initial) (:cid (first (rows)))))
    (swap! timestamp + cache/expiry-seconds)
    (is (unavailable? #(cache/resolve! cache publisher/nsid)))))
