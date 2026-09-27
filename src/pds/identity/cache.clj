(ns pds.identity.cache
  "Bounded process-local public identity cache. No stale-on-error fallback."
  (:require [clojure.data.json :as json]
            [pds.errors :as errors]))

(defn settings [env]
  (let [integer (fn [key default minimum maximum]
                  (let [n (try (Long/parseLong (get env key (str default))) (catch Exception _ -1))]
                    (when-not (<= minimum n maximum)
                      (throw (ex-info (str key " must be between " minimum " and " maximum) {})))
                    n))]
    {:identity-cache-ttl-ms (* 1000 (integer "PDS_IDENTITY_CACHE_TTL_SECONDS" 300 0 3600))
     :identity-cache-size (integer "PDS_IDENTITY_CACHE_MAX_ENTRIES" 1024 1 10000)}))

(defn create [config]
  {:entries (atom {}) :tick (atom 0)
   :ttl-ms (get config :identity-cache-ttl-ms 300000)
   :capacity (get config :identity-cache-size 1024)
   :byte-capacity (* 16 1024 1024)
   :clock #(quot (System/nanoTime) 1000000)})

(defn- busy! [] (errors/raise! 503 "ServiceUnavailable" "Identity resolution is busy; retry later"))
(defn- superseded []
  (ex-info "Identity resolution was refreshed; retry the request"
           {:xrpc true :status 503 :error "ServiceUnavailable"}))

(defn invalidate!
  "Select keys to invalidate from one locked snapshot. In-flight owners are
  fenced by their promise identity, so late completions cannot repopulate them."
  [cache select-keys]
  (when cache
    (let [entries (:entries cache)]
      (locking entries
        (let [keys (select-keys @entries)]
          (doseq [key keys :let [pending (:pending (get @entries key))] :when pending]
            (deliver pending {:error (superseded)}))
          (swap! entries #(apply dissoc % keys)))))))

(defn- evict-oldest [entries]
  (if-let [[key _] (first (sort-by (comp :tick val) (remove (comp :pending val) entries)))]
    (dissoc entries key) entries))

(defn- fit [entries capacity bytes]
  (loop [entries entries]
    (if (and (<= (count entries) capacity)
             (<= (reduce + 0 (map #(get % :bytes 0) (vals entries))) bytes))
      entries
      (let [smaller (evict-oldest entries)]
        (if (= (count smaller) (count entries)) entries (recur smaller))))))

(defn lookup!
  "Coalesce misses. A forced lookup supersedes old work. Errors are never cached;
  waiting callers have a deadline and never take ownership on a timeout."
  [cache key force? load-value]
  (if (or (nil? cache) (zero? (:ttl-ms cache))) (load-value)
    (let [entries (:entries cache) pending (promise)
          action (locking entries
                   (let [now ((:clock cache))
                         current (into {} (filter (fn [[_ entry]] (or (:pending entry) (> (:until entry) now)))) @entries)
                         entry (get current key)]
                     (cond
                       (and (not force?) (:pending entry)) [:wait (:pending entry)]
                       (and (not force?) entry)
                       (do (reset! entries (assoc-in current [key :tick] (swap! (:tick cache) inc))) [:value (:value entry)])
                       :else
                       (let [current (dissoc current key)
                             current (if (>= (count current) (:capacity cache)) (evict-oldest current) current)]
                         (when (>= (count current) (:capacity cache)) (busy!))
                         (when (:pending entry) (deliver (:pending entry) {:error (superseded)}))
                         (reset! entries (assoc current key {:pending pending}))
                         [:load pending]))))]
      (case (first action)
        :value (second action)
        :wait (let [result (deref (second action) 15000 ::timeout)]
                (when (= ::timeout result) (busy!))
                (if-let [error (:error result)] (throw error) (:value result)))
        :load
        (let [result (try
                       (let [value (load-value)
                             size (alength (.getBytes (json/write-str value) "UTF-8"))]
                         (locking entries
                           (if (identical? pending (:pending (get @entries key)))
                             (do
                               (swap! entries dissoc key)
                               (when (<= size (:byte-capacity cache))
                                 (swap! entries #(fit (assoc % key {:value value :bytes size
                                                                   :until (+ ((:clock cache)) (:ttl-ms cache))
                                                                   :tick (swap! (:tick cache) inc)})
                                                     (:capacity cache) (:byte-capacity cache))))
                               {:value value})
                             {:error (superseded)})))
                       (catch Throwable error
                         (locking entries
                           (when (identical? pending (:pending (get @entries key))) (swap! entries dissoc key)))
                         {:error error}))]
          (deliver pending result)
          (if-let [error (:error result)] (throw error) (:value result)))))))
