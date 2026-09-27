(ns pds.record-validation
  "On-demand authenticated record schemas with a bounded process-local cache.
  Explicit validation resolves; optimistic validation uses already known schemas."
  (:require [pds.errors :as errors]
            [pds.identity :as identity]
            [pds.lexicon :as lexicon]
            [pds.lexicon-resolver :as resolver]
            [pds.lexicon-schema :as schema]
            [pds.protocol.codec :as codec])
  (:import [java.util.concurrent Semaphore]))

(def ttl-ms 3600000)
(def retry-ms 60000)
(def capacity 128)
(def byte-capacity (* 16 1024 1024))
(defn cache [lookup]
  {:lookup lookup :entries (atom {}) :permits (Semaphore. 16) :clock #(System/currentTimeMillis)})
(defn settings [config]
  (if (:record-schema-cache config) config
    (let [remote (resolver/resolver {:http-client (:http-client config) :identity-resolver (identity/resolver config)})]
      (assoc config :record-schema-cache (cache #(:schema (resolver/resolve! remote %)))))))
(defn- known [cache collection]
  (let [entry (get @(:entries cache) collection)]
    (when (and (:catalog entry) (> (:until entry) ((:clock cache)))) (:catalog entry))))
(defn- unavailable! [] (errors/raise! 400 "InvalidRecord" "Record schema could not be resolved or is unsupported"))
(defn- resolve! [cache collection deadline]
  (or (known cache collection)
      (let [entries (:entries cache) owner (Object.) now ((:clock cache))
            claimed? (locking entries
                       (let [current @entries entry (get current collection)]
                         (when-not (and entry (or (:owner entry) (> (:until entry) now)))
                           (let [current (into {} (remove (fn [[_ entry]] (and (not (:owner entry)) (<= (:until entry) now)))) current)]
                             (when (< (count current) capacity)
                               (reset! entries (assoc current collection {:owner owner :until now :bytes 0}))
                               true)))))]
        (if-not claimed? (or (known cache collection) (unavailable!))
          (let [entry (try
                        (let [catalog (schema/record-catalog!
                                       (fn [id]
                                         (when (> (System/nanoTime) deadline) (unavailable!))
                                         (let [doc (or (get @lexicon/catalog id) ((:lookup cache) id))]
                                           (when (> (System/nanoTime) deadline) (unavailable!))
                                           doc)) collection)]
                          {:catalog catalog :bytes (alength (codec/encode catalog)) :until (+ ((:clock cache)) ttl-ms)})
                        (catch Exception _ {:bytes 0 :until (+ ((:clock cache)) retry-ms)}))
                admitted? (locking entries
                            (let [current @entries
                                  size (reduce + (:bytes entry) (map :bytes (vals (dissoc current collection))))]
                              (when (identical? owner (:owner (get current collection)))
                                (if (<= size byte-capacity)
                                  (do (swap! entries assoc collection entry) true)
                                  (do (swap! entries assoc collection {:bytes 0 :until (+ ((:clock cache)) retry-ms)}) false)))))]
            (if (and admitted? (:catalog entry)) (:catalog entry) (unavailable!)))))))

(defn prepare!
  "Prepare per-collection immutable catalogs outside all write transactions.
  Skipped/deleted records do no schema work. Missing optimistic schemas remain
  unknown; explicit validation fails closed. No caller-provided catalogs accepted."
  [config writes]
  (let [cache (:record-schema-cache config)
        collections (distinct (keep #(when (and (not= :delete (:action %)) (not (false? (:validate %)))) (:collection %)) writes))
        explicit (set (keep #(when (and (not= :delete (:action %)) (true? (:validate %))) (:collection %)) writes))
        remote (filter #(and (explicit %) (not (contains? @lexicon/catalog %))) collections)]
    (when (and (seq remote) (nil? cache)) (unavailable!))
    (let [^Semaphore permits (:permits cache) acquired? (and (seq remote) (.tryAcquire permits))
          deadline (+ (System/nanoTime) 30000000000)]
      (when (and (seq remote) (not acquired?)) (unavailable!))
      (try
        (reduce (fn [out collection]
                  (let [catalog (cond (contains? @lexicon/catalog collection) @lexicon/catalog
                                      (explicit collection) (resolve! cache collection deadline)
                                      cache (known cache collection))]
                    (cond-> out catalog (assoc collection catalog)))) {} collections)
        (finally (when acquired? (.release permits)))))))
