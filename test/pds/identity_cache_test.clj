(ns pds.identity-cache-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.identity :as identity]
            [pds.identity.cache :as cache]
            [pds.protocol.codec :as codec]))

(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))
(defn response [document] {:status 200 :body (codec/utf8 (json/write-str document))})

(deftest validated-configuration-and-disable
  (is (= {:identity-cache-ttl-ms 300000 :identity-cache-size 1024} (cache/settings {})))
  (is (= {:identity-cache-ttl-ms 0 :identity-cache-size 1}
         (cache/settings {"PDS_IDENTITY_CACHE_TTL_SECONDS" "0" "PDS_IDENTITY_CACHE_MAX_ENTRIES" "1"})))
  (doseq [[key values] [["PDS_IDENTITY_CACHE_TTL_SECONDS" ["-1" "3601" "no" ""]]
                        ["PDS_IDENTITY_CACHE_MAX_ENTRIES" ["0" "10001" "1.5" ""]]]
          value values]
    (is (thrown? clojure.lang.ExceptionInfo (identity/settings {key value}))))
  (let [c (cache/create {:identity-cache-ttl-ms 0}) calls (atom 0)]
    (is (= [1 2] (mapv (fn [_] (cache/lookup! c :key false #(swap! calls inc))) (range 2))))
    (is (empty? @(:entries c)))))

(deftest expiry-entry-and-byte-bounds
  (let [time (atom 0) calls (atom 0)
        c (assoc (cache/create {:identity-cache-ttl-ms 10 :identity-cache-size 2}) :clock #(deref time))
        lookup #(cache/lookup! c % false (fn [] (swap! calls inc)))]
    (is (= 1 (lookup :a) (lookup :a)))
    (is (= 2 (lookup :b)))
    (is (= 1 (lookup :a)))
    (is (= 3 (lookup :c)))
    (is (= #{:a :c} (set (keys @(:entries c)))) "Least recently used completed value is evicted")
    (reset! time 10)
    (is (= 4 (lookup :a)))
    (is (= #{:a} (set (keys @(:entries c))))))
  (let [c (assoc (cache/create {}) :byte-capacity 12)]
    (cache/lookup! c :a false (constantly "123456"))
    (cache/lookup! c :b false (constantly "789012"))
    (is (= #{:b} (set (keys @(:entries c)))))
    (is (= "oversized-document" (cache/lookup! c :big false (constantly "oversized-document"))))
    (is (= #{:b} (set (keys @(:entries c)))))))

(deftest errors-are-not-cached-and-refresh-has-no-stale-fallback
  (let [c (cache/create {})]
    (is (= :old (cache/lookup! c :key false (constantly :old))))
    (is (= "DidResolutionFailed" (error #(cache/lookup! c :key true
                                          (fn [] (throw (ex-info "failed" {:error "DidResolutionFailed"})))))))
    (is (empty? @(:entries c)))
    (is (= :new (cache/lookup! c :key false (constantly :new))))))

(deftest concurrent-misses-share-work-and-refresh-fences-old-results
  (let [c (cache/create {}) started (promise) release (promise) calls (atom 0)
        owner (future (error #(cache/lookup! c :did false
                                (fn [] (swap! calls inc) (deliver started true) (deref release 5000 nil) :old))))]
    (try
      (is (= true (deref started 5000 :timeout)))
      (let [follower (future (try [:value (cache/lookup! c :did false (fn [] (swap! calls inc) :unexpected))]
                                 (catch clojure.lang.ExceptionInfo e [:error (:error (ex-data e))])))]
        (is (= :pending (deref follower 100 :pending)))
        (is (= :new (cache/lookup! c :did true (constantly :new))))
        (deliver release true)
        (is (= "ServiceUnavailable" (deref owner 5000 :timeout)))
        ;; A delayed follower may start after the refresh; it must then see
        ;; the new value, never the old in-flight value or a duplicate load.
        (is (contains? #{[:error "ServiceUnavailable"] [:value :new]} (deref follower 5000 :timeout)))
        (is (= :new (cache/lookup! c :did false (constantly :unexpected))))
        (is (= 1 @calls)))
      (finally (deliver release true) (deref owner 5000 nil))))
  (let [c (cache/create {:identity-cache-size 1}) started (promise) release (promise)
        owner (future (cache/lookup! c :one false #(do (deliver started true) (deref release 5000 nil) :one)))]
    (try
      (is (= true (deref started 5000 :timeout)))
      (is (= "ServiceUnavailable" (error #(cache/lookup! c :two false (constantly :two)))))
      (is (= 1 (count @(:entries c))))
      (deliver release true)
      (is (= :one (deref owner 5000 :timeout)))
      (finally (deliver release true) (deref owner 5000 nil)))))

(deftest identity-refresh-rechecks-bindings-and-local-reads-bypass-cache
  (let [did "did:web:alice.example.com" other "did:web:bob.example.com"
        doc (atom {"id" did "alsoKnownAs" ["at://alice.example.com"]})
        binding (atom did) calls (atom []) local (atom nil)
        c (cache/create {})
        r (identity/resolver {:identity-cache c
                              :local-document (fn [_] @local)
                              :txt-lookup (fn [name] (swap! calls conj name) [(str "did=" @binding)])
                              :fetch (fn [url _] (swap! calls conj url) (response @doc))})]
    (is (= "alice.example.com" (:handle (identity/resolve-identity! r "ALICE.example.com"))))
    (is (= 2 (count @calls)))
    (is (= "alice.example.com" (:handle (identity/resolve-identity! r did))))
    (is (= 2 (count @calls)))
    (reset! binding other)
    (is (= "handle.invalid" (:handle (identity/refresh-identity! r did))))
    (is (= 4 (count @calls)))
    (is (= other (identity/resolve-handle! r "alice.example.com")))
    (reset! doc {"id" other})
    (is (= other (:did (identity/refresh-identity! r "alice.example.com"))))
    (is (= {"id" other} (identity/resolve-did! r other)))
    (reset! local {"id" did "alsoKnownAs" ["at://local.example.com"]})
    (is (= @local (identity/resolve-did! r did)))
    (reset! local {"id" did "alsoKnownAs" []})
    (is (= @local (identity/resolve-did! r did)))
    ;; Internal resolvers deliberately do not receive the public endpoint cache.
    (let [fresh (identity/resolver {:fetch (fn [_ _] (swap! calls conj :fresh) (response @doc))})]
      (identity/resolve-did! fresh other)
      (identity/resolve-did! fresh other)
      (is (= 2 (count (filter #{:fresh} @calls)))))))
