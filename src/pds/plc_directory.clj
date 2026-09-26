(ns pds.plc-directory
  (:require [clojure.data.json :as json]
            [pds.identity :as identity]
            [pds.net :as net]
            [pds.plc :as plc]
            [pds.protocol.codec :as codec]
            [pds.request :as request]))

(defn- fail! [reason retryable?]
  ;; Never expose directory response bodies or transport exception details.
  (throw (ex-info "PLC directory operation could not be confirmed"
                  {:type :plc-directory :reason reason :retryable retryable?})))

(defn- did-url [origin did]
  (when-not (and (string? did) (re-matches #"did:plc:[a-z2-7]{24}" did)) (fail! :invalid-did false))
  (str (identity/origin! origin) "/" did))

(defn audit!
  "Fetch and verify the full audit log. Returns nil only on HTTP 404. Size,
  nesting, operation count, signatures, CIDs and recovery decisions are checked.
  The directory remains trusted for acceptance timestamps and log freshness."
  [client origin did]
  (let [url (str (did-url origin did) "/log/audit")
        response (try (net/fetch! client url {:maximum (* 4 1024 1024) :timeout-ms 5000 :redirects 0})
                      (catch Exception _ (fail! :unavailable true)))
        status (:status response)]
    (cond
      (= 404 status) nil
      (not= 200 status) (fail! :unavailable true)
      :else (try
              (let [entries (request/json-value (:body response))
                    verified (plc/verify-audit! did entries)]
                (assoc verified :entries entries))
              (catch Exception _ (fail! :invalid-audit false))))))

(defn ensure-operation!
  "Confirm or submit a normal successor operation (or genesis), then prove it is
  the current canonical head. Never replaces a different head. Retrying after a
  crash/timeout is safe because the signed operation and CID are unchanged.
  Recovery forks require a separate explicit flow and are not submitted here."
  [client origin did op]
  (plc/operation! op)
  (let [url (did-url origin did)
        target (plc/operation-cid op)
        before (audit! client origin did)]
    (if (= target (:head before))
      before
      (do
        (if before
          (do
            (when-not (and (:data before) (= (:head before) (get op "prev"))) (fail! :conflict false))
            (plc/signer! (get-in before [:data "rotationKeys"]) op))
          (plc/verify-genesis! did op))
        (let [submission (try
                           {:status (:status (net/post-json! client url (codec/utf8 (json/write-str op))))}
                           (catch Exception _ {:uncertain true}))
              after (audit! client origin did)]
          (cond
            (= target (:head after)) after
            (and after (not= (:head before) (:head after))) (fail! :conflict false)
            (and (:status submission) (<= 400 (:status submission) 499)
                 (not (#{408 429} (:status submission)))) (fail! :rejected false)
            (and (:status submission) (<= 300 (:status submission) 399)) (fail! :redirect false)
            :else (fail! :unconfirmed true)))))))
