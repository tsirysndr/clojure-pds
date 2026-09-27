(ns pds.lexicon-resolver
  "Authenticated, bounded resolution of published Lexicon records. Caching and
  permission-set interpretation are separate from this chain of authority."
  (:require [clojure.string :as str]
            [pds.identity :as identity]
            [pds.net :as net]
            [pds.protocol.repository :as repository]
            [pds.protocol.syntax :as syntax])
  (:import [java.net URLEncoder]
           [java.util.concurrent Semaphore]))

(defn- fail! [reason]
  (throw (ex-info "Lexicon resolution failed" {:lexicon-error reason})))

(defn authority-name [nsid]
  (when-not (syntax/nsid? nsid) (fail! :invalid-nsid))
  (let [name (str "_lexicon." (str/join "." (reverse (butlast (str/split (str/lower-case nsid) #"\.")))))]
    (when (> (count name) 253) (fail! :invalid-authority))
    (str name ".")))

(defn resolver
  "Share the application's guarded HTTP client and identity resolver. Overrides
  are explicit test dependencies, never permission to bypass network policies."
  [{:keys [identity-resolver http-client txt-lookup fetch]}]
  {:identity identity-resolver :txt-lookup (or txt-lookup (:txt-lookup identity-resolver))
   :fetch (or fetch #(net/fetch! http-client %1 %2)) :permits (Semaphore. 16)})

(defn- authority! [resolver nsid]
  (let [records ((:txt-lookup resolver) (authority-name nsid))
        candidates (set (keep #(when (and (string? %) (str/starts-with? % "did=")) (subs % 4)) records))]
    ;; An unsupported/invalid competing DID is still an ambiguity, not an excuse
    ;; to select whichever record this implementation happens to understand.
    (when-not (and (= 1 (count candidates)) (identity/supported-did? (first candidates)))
      (fail! :authority))
    (first candidates)))

(defn resolve!
  "DNS exact namespace -> DID/key/PDS -> signed sync.getRecord CAR -> schema.
  Returns provenance with the schema. No DNS parent fallback or unsigned JSON
  fallback; every invocation re-resolves authority. No database lock is needed."
  [resolver nsid]
  (authority-name nsid)
  (let [^Semaphore permits (:permits resolver)]
    (when-not (.tryAcquire permits) (fail! :busy))
    (try
      (let [did (authority! resolver nsid)
            document (identity/bounded-call! (:identity resolver)
                       #(identity/resolve-did! (:identity resolver) did))
            _ (when-not (= did (get document "id")) (fail! :identity))
            key (identity/signing-key document) endpoint (identity/pds-endpoint document)]
        (when-not (and key endpoint) (fail! :identity))
        (let [url (str endpoint "/xrpc/com.atproto.sync.getRecord?did=" (URLEncoder/encode did "UTF-8")
                       "&collection=com.atproto.lexicon.schema&rkey=" (URLEncoder/encode nsid "UTF-8"))
              {:keys [status body]} ((:fetch resolver) url {:maximum (* 2 1024 1024) :timeout-ms 5000 :redirects 0})]
          (when-not (= 200 status) (fail! :fetch))
          (when-not (and (bytes? body) (<= (alength ^bytes body) (* 2 1024 1024))) (fail! :size))
          (let [{:keys [record cid head rev]} (repository/verify-record body did key "com.atproto.lexicon.schema" nsid)]
            (when-not record (fail! :not-found))
            (when-not (and (= "com.atproto.lexicon.schema" (get record "$type"))
                           (= 1 (get record "lexicon")) (= nsid (get record "id"))
                           (map? (get record "defs")) (seq (get record "defs"))
                           (every? (fn [[name definition]]
                                     (and (string? name) (re-matches #"[A-Za-z][A-Za-z0-9]*" name)
                                          (map? definition) (string? (get definition "type")))) (get record "defs")))
              (fail! :schema))
            {:nsid nsid :did did :cid cid :head head :rev rev :schema record})))
      (catch Exception e
        ;; Untrusted DNS/HTTP payloads and exception messages are not reflected.
        (if (:lexicon-error (ex-data e)) (throw e) (fail! :verification)))
      (finally (.release permits)))))
