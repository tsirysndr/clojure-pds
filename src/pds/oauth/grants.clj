(ns pds.oauth.grants
  "Persisted permission-set snapshots shared by PAR, consent and token issuance.
  Resolution is explicit and never occurs during resource authorization."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.identity :as identity]
            [pds.lexicon-resolver :as lexicon]
            [pds.oauth.http :as http]
            [pds.oauth.permission-cache :as cache]
            [pds.oauth.permission-sets :as sets]
            [pds.oauth.scope :as scope]
            [pds.protocol.codec :as codec]))

(defn settings [ds settings]
  (if (:oauth-permission-cache settings) settings
    (assoc settings :oauth-permission-cache
           (cache/cache ds (lexicon/resolver {:http-client (:http-client settings)
                                             :identity-resolver (identity/resolver settings)})))))
(defn- tokens [value] (str/split (or value "") #" "))
(defn includes [value] (keep scope/parse-include (tokens value)))
(defn validate-scope! [value]
  (when (> (count (set (map :nsid (includes value)))) 16)
    (http/fail! "invalid_scope" "Too many permission sets")))
(defn- freeze [entry]
  (into {"schema" (crypto/b64 (codec/encode (:schema entry)))}
        (map (fn [key] [(name key) (get entry key)]) [:nsid :did :cid :head :rev :fetched-at])))
(defn- thaw [entry]
  (when entry
    (assoc (into {} (map (fn [key] [key (get entry (name key))]) [:nsid :did :cid :head :rev :fetched-at]))
           :schema (codec/decode (crypto/unb64 (get entry "schema")) 1000000))))
(defn- bounded! [snapshots]
  (when (> (alength (codec/utf8 (json/write-str snapshots))) (* 4 1024 1024))
    (http/fail! "invalid_scope" "Permission set snapshot is too large"))
  snapshots)
(defn merge-snapshots [previous refreshed] (bounded! (merge previous refreshed)))
(defn resolve!
  "Resolve the requested sets outside grant transactions. Previous snapshots are
  trusted session state only; never accept them from request parameters."
  [ds config value previous]
  (validate-scope! value)
  (let [requested (distinct (map :nsid (includes value)))
        resolver (:oauth-permission-cache (when (seq requested) (settings ds config)))
        deadline (+ (System/nanoTime) 30000000000)]
    (reduce (fn [out nsid]
              (when (> (System/nanoTime) deadline)
                (http/fail! "temporarily_unavailable" "Permission set resolution timed out"))
              (let [resolved (cache/resolve! resolver nsid (thaw (get previous nsid)))]
                (when (> (System/nanoTime) deadline)
                  (http/fail! "temporarily_unavailable" "Permission set resolution timed out"))
                (bounded! (assoc out nsid (freeze resolved))))) {} requested)))
(defn- expansion [token snapshots]
  (let [nsid (:nsid (scope/parse-include token)) entry (thaw (get snapshots nsid))]
    (when-not (= nsid (:nsid entry)) (http/fail! "invalid_scope" "Missing permission set snapshot"))
    (sets/expand token (:schema entry))))
(defn permissions [value snapshots]
  (let [permissions (vec (distinct (mapcat (fn [token]
                                           (if (scope/parse-include token) (:permissions (expansion token snapshots))
                                             (if (scope/parse token) [token] []))) (tokens value))))]
    (when (or (> (count permissions) 10000) (> (reduce + 0 (map count permissions)) 1000000)
              (not-every? scope/parse permissions))
      (http/fail! "invalid_scope" "Expanded permissions exceed supported limits"))
    permissions))
(defn describe [value snapshots]
  {:permissions (vec (mapcat #(scope/describe %) (remove scope/parse-include (tokens value))))
   :permission-sets (mapv (fn [token]
                            (let [expanded (expansion token snapshots)]
                              (update expanded :permissions #(vec (mapcat scope/describe %)))))
                          (filter scope/parse-include (tokens value)))})
