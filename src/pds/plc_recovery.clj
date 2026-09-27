(ns pds.plc-recovery
  "Explicit submission of externally signed PLC recovery forks. Does not read
  private keys or mutate local accounts; adoption uses PLC reconciliation."
  (:require [clojure.data.json :as json]
            [pds.identity :as identity]
            [pds.net :as net]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.protocol.codec :as codec])
  (:import [java.time Instant]))

(defn- fail! [reason retryable?]
  (throw (ex-info "PLC recovery could not be confirmed"
                  {:type :plc-recovery :reason reason :retryable retryable?})))
(defn identifiers! [did & cids]
  (when-not (and (string? did) (re-matches #"did:plc:[a-z2-7]{24}" did)) (fail! :invalid-input false))
  (doseq [cid cids]
    (when-not (try (= 113 (aget ^bytes (codec/cid-bytes cid) 1)) (catch Exception _ false))
      (fail! :invalid-input false))))
(defn operation! [op]
  (try
    (plc/operation! op)
    (when-not (and (#{"plc_operation" "plc_tombstone"} (get op "type")) (get op "prev"))
      (fail! :invalid-operation false))
    op
    (catch Exception _ (fail! :invalid-operation false))))
(defn- audit! [client origin did]
  (or (directory/audit! client origin did) (fail! :identity-missing false)))
(defn- observed [did audit op]
  (let [cid (plc/operation-cid op)]
    (when (some #(= cid (get % "cid")) (:entries audit))
      {:did did :operationCid cid :previousCid (get op "prev") :remoteCid (:head audit)
       :state (cond (contains? (:nullified audit) cid) "superseded"
                    (= cid (:head audit)) "confirmed"
                    :else "accepted")
       :auditNullifiedCids (vec (sort (:nullified audit)))
       :directoryData (:data audit)})))
(defn- plan! [did audit op]
  (try
    (assoc (dissoc (plc/recovery-plan! did (:entries audit) op (Instant/now)) :entries) :did did :state "ready")
    (catch Exception _ (fail! :invalid-recovery false))))

(defn inspect! [client origin did op]
  (identifiers! did)
  (operation! op)
  (let [audit (audit! client origin did)] (or (observed did audit op) (plan! did audit op))))

(defn submit!
  "Submit only the reviewed operation against the observed remote head. The PLC
  API has no compare-and-set: a race can extend the nullified branch. Verify the
  full post-submission audit, including after an ambiguous HTTP response. A seen
  CID is never posted again, even if it is now nullified or has descendants."
  [client origin did expected-remote expected-operation op]
  (identifiers! did expected-remote expected-operation)
  (operation! op)
  (when-not (= expected-operation (plc/operation-cid op)) (fail! :operation-mismatch false))
  (let [origin (identity/origin! origin) before (audit! client origin did)]
    (or (observed did before op)
        (do
          (when-not (= expected-remote (:head before)) (fail! :head-mismatch false))
          (plan! did before op)
          (let [response (try {:status (:status (net/post-json! client (str origin "/" did) (codec/utf8 (json/write-str op))))}
                              (catch Exception _ {:uncertain true}))
                after (audit! client origin did)]
            (or (observed did after op)
                (cond
                  (not= (:head before) (:head after)) (fail! :head-mismatch false)
                  (and (:status response) (<= 400 (:status response) 499) (not (#{408 429} (:status response)))) (fail! :rejected false)
                  (and (:status response) (<= 300 (:status response) 399)) (fail! :redirect false)
                  :else (fail! :unconfirmed true))))))))
