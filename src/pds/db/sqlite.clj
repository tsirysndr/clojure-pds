(ns pds.db.sqlite
  "SQLite dialect support. The persistence code is written against PostgreSQL;
  this namespace translates its finite construct set at the statement boundary,
  binds parameters in SQLite-compatible encodings, and coerces reads back to
  the Java types the PostgreSQL driver would return. Transactions run in
  IMMEDIATE mode, so the single writer provides the exclusion that row locks
  and advisory locks provide on PostgreSQL."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.sql Connection Timestamp]
           [java.time Instant LocalDateTime ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util UUID]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.function Function]
           [org.sqlite SQLiteConfig SQLiteConfig$JournalMode SQLiteConfig$SynchronousMode SQLiteConfig$TransactionMode SQLiteConnection SQLiteDataSource]))

(defn sqlite-url? [url] (and (string? url) (str/starts-with? url "jdbc:sqlite:")))
(defn sqlite-connection? [^Connection conn] (.isWrapperFor conn SQLiteConnection))

(defn datasource ^SQLiteDataSource [url]
  (let [path (subs url (count "jdbc:sqlite:"))]
    (when-not (or (str/blank? path) (str/starts-with? path ":memory:") (str/includes? path "mode=memory"))
      (io/make-parents (io/file path)))
    (doto (SQLiteDataSource.
            (doto (SQLiteConfig.)
              (.setJournalMode SQLiteConfig$JournalMode/WAL)
              (.setSynchronous SQLiteConfig$SynchronousMode/NORMAL)
              (.enforceForeignKeys true)
              (.setBusyTimeout 30000)
              (.setTransactionMode SQLiteConfig$TransactionMode/IMMEDIATE)))
      (.setUrl url))))

(def ^DateTimeFormatter timestamp-format
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSS") (.withZone ZoneOffset/UTC)))

(defn format-instant [^Instant instant] (.format timestamp-format instant))
(defn- parse-timestamp ^Timestamp [^String value]
  (Timestamp/from (.toInstant (.atOffset (LocalDateTime/parse value timestamp-format) ZoneOffset/UTC))))

(defn bind-value [value]
  (cond
    (instance? Instant value) (format-instant value)
    (instance? Timestamp value) (format-instant (.toInstant ^Timestamp value))
    (instance? UUID value) (str value)
    :else value))

(defn coerce-read [type-name value]
  (if (nil? value)
    nil
    (case (some-> type-name str/lower-case)
      "timestamptz" (if (string? value) (parse-timestamp value) value)
      "boolean" (if (number? value) (not (zero? (long value))) value)
      "uuid" (if (string? value) (UUID/fromString value) value)
      value)))

(def now-expr "strftime('%Y-%m-%d %H:%M:%f','now')")

(def ^:private rules
  ;; Ordered: special cases before the generic cast/keyword removals.
  [[#"(?s)SELECT EXISTS \(SELECT 1 FROM pg_locks.*?\) AS held" "SELECT 1 AS held"]
   [#"set_config\('[^']*', '[^']*', \w+\)" "'clojure-pds'"]
   [#"pg_advisory_xact_lock\(\d+\)" "1"]
   [#"pg_(?:try_advisory_(?:xact_)?lock(?:_shared)?|advisory_xact_lock|advisory_unlock_shared)\((?:hashtextextended\()?\?(?:, 0\))?\)"
    "(ifnull(?, 0) IS NOT NULL)"]
   [#"substring\((\w+) FROM \?(?:::integer)? FOR \?(?:::integer)?\)" "substr($1, ?, ?)"]
   [#"min\(seq\) FILTER \(WHERE created_at >= \?\)" "min(CASE WHEN created_at >= ? THEN seq END)"]
   [#"extract\(epoch FROM ([\w.]+) - ([\w.]+)\)"
    "(CAST(strftime('%s',$1) AS INTEGER) - CAST(strftime('%s',$2) AS INTEGER))"]
   [#"\(extract\(epoch FROM ([\w.]+)\) \* 1000000\)::bigint"
    "(CAST(strftime('%s',$1) AS INTEGER)*1000000 + CAST(substr($1,21,3) AS INTEGER)*1000)"]
   [#"now\(\) \+ \(\? \* interval '1 second'\)"
    (str "strftime('%Y-%m-%d %H:%M:%f','now', printf('+%d seconds', ?))")]
   [#"now\(\) - \(\? \* interval '1 second'\)"
    (str "strftime('%Y-%m-%d %H:%M:%f','now', printf('-%d seconds', ?))")]
   [#"now\(\) \+ interval '(\d+) (\w+)'" "strftime('%Y-%m-%d %H:%M:%f','now','+$1 $2')"]
   [#"now\(\) - interval '(\d+) (\w+)'" "strftime('%Y-%m-%d %H:%M:%f','now','-$1 $2')"]
   [#"DEFAULT now\(\)" (str "DEFAULT (" now-expr ")")]
   [#"\bnow\(\)" now-expr]
   [#"jsonb_set\(([\w.]+), '\{([\w-]+)\}', \?::jsonb\)" "json_set($1, '\\$.\"$2\"', json(?))"]
   [#"([\w.]+)->'([\w-]+)'->>'([\w-]+)'" "json_extract($1, '\\$.\"$2\".\"$3\"')"]
   [#"\(([\w.]+)->'([\w-]+)'\)::text" "json_extract($1, '\\$.\"$2\"')"]
   [#"([\w.]+)->>'([\w-]+)'" "json_extract($1, '\\$.\"$2\"')"]
   [#"jsonb_array_length\(" "json_array_length("]
   [#"'((?:[^'])*)'::jsonb" "'$1'"]
   [#"::(?:text|bigint|integer|timestamptz|jsonb|uuid)\b" ""]
   [#" FOR UPDATE(?: OF \w+)?(?: SKIP LOCKED)?" ""]
   [#" COLLATE \"C\"" ""]
   [#"octet_length\(" "length("]])

(def ^:private leftovers
  #"::|pg_|jsonb|\binterval\b|FILTER \(|now\(\)|COLLATE|->>|octet_length")

(defn- translate* [sql]
  (cond
    (re-find #"(?i)^\s*(?:SET LOCAL|LOCK TABLE)\b" sql) ::noop
    (str/includes? sql "CREATE TEMPORARY TABLE pds_car_export_seen")
    ["DROP TABLE IF EXISTS pds_car_export_seen"
     "CREATE TEMP TABLE pds_car_export_seen (cid text PRIMARY KEY)"]
    :else
    (let [result (reduce (fn [text [pattern replacement]] (str/replace text pattern replacement)) sql rules)]
      (when (re-find leftovers result)
        (throw (ex-info "SQL construct is not translated for the SQLite backend"
                        {:sql sql :translated result})))
      result)))

(def ^:private ^ConcurrentHashMap cache (ConcurrentHashMap.))

(defn translate [sql]
  (.computeIfAbsent cache sql (reify Function (apply [_ sql] (translate* sql)))))

(defn noop? [translated] (= ::noop translated))

(defn script-statements
  "Split a migration script into statements. Trigger bodies keep their inner
  semicolons; everything else ends at a line-terminating semicolon."
  [script]
  (loop [lines (str/split-lines script) current [] trigger? false statements []]
    (if-let [line (first lines)]
      (let [current (conj current line)
            trimmed (str/trim line)
            trigger? (or trigger? (str/starts-with? (str/upper-case trimmed) "CREATE TRIGGER"))
            ends? (and (str/ends-with? trimmed ";")
                       (or (not trigger?) (= "END;" (str/upper-case trimmed))))]
        (if ends?
          (recur (rest lines) [] false (conj statements (str/join "\n" current)))
          (recur (rest lines) current trigger? statements)))
      (let [tail (str/trim (str/join "\n" current))]
        (into [] (remove #(str/blank? (str/replace % #"(?m)^\s*--.*$" "")))
              (cond-> statements (seq tail) (conj tail)))))))
