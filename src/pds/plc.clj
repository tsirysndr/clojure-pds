(ns pds.plc
  "Pure PLC operation primitives. This namespace neither submits operations nor
  treats directory timestamps as cryptographically authenticated."
  (:require [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.protocol.codec :as codec])
  (:import [java.time Duration Instant]))

(def max-operation-bytes 7500)
(def recovery-window (Duration/ofHours 72))
(defn- fail! [message] (throw (ex-info message {:type :invalid-plc})))
(defn did-key [key] (str "did:key:" (crypto/multikey (:algorithm key) (:public key))))
(defn parse-key [value]
  (when-not (and (string? value) (str/starts-with? value "did:key:")) (fail! "Expected did:key"))
  (crypto/parse-multikey (subs value 8)))
(defn- cid! [value]
  (when-not (= 113 (aget ^bytes (codec/cid-bytes value) 1)) (fail! "PLC prev must use DAG-CBOR CID"))
  value)
(defn- string-map? [value pred]
  (and (map? value) (every? (fn [[k v]] (and (string? k) (pred v))) value)))
(defn- did-key-syntax? [value]
  (and (string? value) (re-matches #"did:key:z[1-9A-HJ-NP-Za-km-z]+" value)))

(defn operation!
  "Validate exact PLC object shape and bounded canonical encoding. A modern
  verification method can use any syntactically valid did:key; rotation keys
  must use one of PLC's two supported curves. Legacy creates remain hashable in
  their original form, with no normalization before signing or DID derivation."
  ([op] (operation! op true))
  ([op signed?]
   (let [fields (case (get op "type")
                  "plc_operation" #{"type" "prev" "rotationKeys" "verificationMethods" "alsoKnownAs" "services"}
                  "plc_tombstone" #{"type" "prev"}
                  "create" #{"type" "prev" "signingKey" "recoveryKey" "handle" "service"}
                  (fail! "Unknown PLC operation type"))]
     (when-not (= (set (keys op)) (cond-> fields signed? (conj "sig"))) (fail! "Invalid PLC operation fields"))
     (when (> (alength ^bytes (codec/encode op)) max-operation-bytes) (fail! "PLC operation exceeds 7500 bytes"))
     (when signed?
       (let [sig (get op "sig")]
         (when-not (and (string? sig) (re-matches #"[A-Za-z0-9_-]{86}" sig)
                        (= sig (crypto/b64 (crypto/unb64 sig))))
           (fail! "Noncanonical PLC signature encoding"))))
     (when (some? (get op "prev")) (cid! (get op "prev")))
     (case (get op "type")
       "plc_tombstone" (when-not (get op "prev") (fail! "Tombstone requires a previous operation"))
       "create" (do (when (get op "prev") (fail! "Legacy creation must be genesis"))
                    (doseq [field ["signingKey" "recoveryKey"]] (parse-key (get op field)))
                    (when-not (every? string? [(get op "handle") (get op "service")]) (fail! "Invalid legacy PLC fields")))
       "plc_operation"
       (let [rotation (get op "rotationKeys")]
         (when-not (and (vector? rotation) (<= 1 (count rotation) 5) (= (count rotation) (count (set rotation))))
           (fail! "PLC requires one to five distinct rotation keys"))
         (doseq [key rotation] (parse-key key))
         (when-not (and (string-map? (get op "verificationMethods") did-key-syntax?)
                        (vector? (get op "alsoKnownAs")) (every? string? (get op "alsoKnownAs"))
                        (string-map? (get op "services") #(and (map? %) (= #{"type" "endpoint"} (set (keys %)))
                                                               (every? string? (vals %)))))
           (fail! "Invalid PLC identity metadata"))))
     op)))

(defn normalize [op]
  (if (= "create" (get op "type"))
    {"type" "plc_operation" "prev" nil "sig" (get op "sig")
     "rotationKeys" [(get op "recoveryKey") (get op "signingKey")]
     "verificationMethods" {"atproto" (get op "signingKey")}
     "alsoKnownAs" [(if (str/starts-with? (get op "handle") "at://") (get op "handle")
                         (str "at://" (str/replace (get op "handle") #"^https?://" "")))]
     "services" {"atproto_pds" {"type" "AtprotoPersonalDataServer"
                                 "endpoint" (if (re-find #"^https?://" (get op "service")) (get op "service")
                                                (str "https://" (get op "service")))}}}
    op))

(defn sign-operation [op key]
  (operation! op false)
  (operation! (assoc op "sig" (crypto/b64 (crypto/sign (:algorithm key) (:private key) (codec/encode op))))))

(defn operation-cid [op] (codec/cid (codec/encode (operation! op))))
(defn genesis-did [op]
  (operation! op)
  (when (or (some? (get op "prev")) (= "plc_tombstone" (get op "type"))) (fail! "Expected genesis operation"))
  (str "did:plc:" (subs (codec/base32 (codec/sha256 (codec/encode op))) 0 24)))

(defn signer!
  "Return the highest-priority allowed key that verifies the signed operation."
  [allowed op]
  (operation! op)
  (let [bytes (codec/encode (dissoc op "sig")) signature (crypto/unb64 (get op "sig"))]
    (or (some (fn [value] (let [key (parse-key value)]
                           (when (crypto/verify (:algorithm key) (:public key) bytes signature) value))) allowed)
        (fail! "PLC signature is not authorized"))))

(defn verify-genesis! [did op]
  (when-not (= did (genesis-did op)) (fail! "PLC genesis hash does not match DID"))
  (signer! (get (normalize op) "rotationKeys") op)
  op)

(defn operation-data [did op]
  (when-not (= "plc_tombstone" (get op "type"))
    (assoc (select-keys (normalize op) ["rotationKeys" "verificationMethods" "alsoKnownAs" "services"]) "did" did)))

(defn verify-log!
  "Verify a canonical operation chain, including legacy genesis and tombstones.
  Audit recovery decisions separately with verify-audit! when nullified entries
  and directory timestamps are available."
  [did operations]
  (when-not (and (vector? operations) (<= 1 (count operations) 10000)) (fail! "Invalid PLC log length"))
  (verify-genesis! did (first operations))
  (loop [previous (first operations) remaining (next operations)]
    (if-let [op (first remaining)]
      (do
        (operation! op)
        (when (or (= "create" (get op "type")) (= "plc_tombstone" (get previous "type"))
                  (not= (operation-cid previous) (get op "prev"))) (fail! "Misordered PLC operation"))
        (signer! (get (normalize previous) "rotationKeys") op)
        (recur op (next remaining)))
      {:head (operation-cid previous) :data (operation-data did previous)})))

(defn did-document [data]
  (when data
    (let [did (get data "did")]
      {"@context" ["https://www.w3.org/ns/did/v1" "https://w3id.org/security/multikey/v1"]
       "id" did "alsoKnownAs" (get data "alsoKnownAs")
       "verificationMethod" (mapv (fn [[id key]] {"id" (str did "#" id) "type" "Multikey" "controller" did
                                                  "publicKeyMultibase" (subs key 8)}) (sort-by key (get data "verificationMethods")))
       "service" (mapv (fn [[id service]] {"id" (str did "#" id) "type" (get service "type")
                                            "serviceEndpoint" (get service "endpoint")}) (sort-by key (get data "services")))})))

(defn verify-audit!
  "Recompute canonical history and nullification flags from the complete audit
  log in acceptance order. Recovery requires a higher-priority key and a 72-hour
  window. Timestamps are directory assertions, not signed by rotation keys."
  [did entries]
  (when-not (and (vector? entries) (<= 1 (count entries) 10000)) (fail! "Invalid PLC audit length"))
  (let [result
        (reduce
          (fn [{:keys [chain seen nullified last-time]} entry]
            (let [op (get entry "operation") cid (operation-cid op)
                  time (try (Instant/parse (get entry "createdAt")) (catch Exception _ (fail! "Invalid PLC audit timestamp")))
                  row {:cid cid :operation op :time time}]
              (when-not (and (= did (get entry "did")) (= cid (get entry "cid"))
                             (boolean? (get entry "nullified")) (not (contains? seen cid))
                             (or (nil? last-time) (not (.isBefore time last-time))))
                (fail! "Invalid PLC audit entry"))
              (if (empty? chain)
                (do (verify-genesis! did op) {:chain [row] :seen #{cid} :nullified #{} :last-time time})
                (let [index (first (keep-indexed #(when (= (:cid %2) (get op "prev")) %1) chain))]
                  (when (or (nil? index) (= "create" (get op "type"))) (fail! "PLC prev is outside canonical history"))
                  (let [previous (:operation (nth chain index))
                        discarded (subvec chain (inc index))
                        keys (get (normalize previous) "rotationKeys")]
                    (when (= "plc_tombstone" (get previous "type")) (fail! "Cannot extend PLC tombstone"))
                    (if-let [disputed (first discarded)]
                      (let [disputed-signer (signer! keys (:operation disputed))
                            priority (.indexOf ^java.util.List keys disputed-signer)]
                        (signer! (subvec keys 0 priority) op)
                        (when (or (not (.isAfter time last-time))
                                  (pos? (.compareTo (Duration/between (:time disputed) time) recovery-window)))
                          (fail! "PLC recovery window expired or timestamp is misordered")))
                      (signer! keys op))
                    {:chain (conj (subvec chain 0 (inc index)) row) :seen (conj seen cid)
                     :nullified (into nullified (map :cid discarded)) :last-time time})))))
          {:chain [] :seen #{} :nullified #{} :last-time nil} entries)]
    (doseq [entry entries]
      (when-not (= (contains? (:nullified result) (get entry "cid")) (get entry "nullified"))
        (fail! "PLC audit nullification flags disagree with recovery rules")))
    (let [head (peek (:chain result))]
      {:head (:cid head) :data (operation-data did (:operation head)) :nullified (:nullified result)})))

(defn recovery-plan!
  "Verify an unseen signed recovery fork at the proposed acceptance time. Returns
  the affected canonical branch and a verified hypothetical audit. This is a
  preflight, not proof that the directory accepted the operation."
  [did entries operation ^Instant time]
  (let [audit (verify-audit! did entries)
        cid (operation-cid operation)
        canonical (filterv #(not (get % "nullified")) entries)
        index (first (keep-indexed #(when (= (get operation "prev") (get %2 "cid")) %1) canonical))]
    (when (or (some #(= cid (get % "cid")) entries) (nil? index) (= index (dec (count canonical))))
      (fail! "Expected an unseen recovery fork from a canonical ancestor"))
    (let [discarded (subvec canonical (inc index))
          cids (mapv #(get % "cid") discarded) removed (set cids)
          proposed (conj (mapv #(if (removed (get % "cid")) (assoc % "nullified" true) %) entries)
                         {"did" did "cid" cid "operation" operation "createdAt" (str time) "nullified" false})
          verified (verify-audit! did proposed)
          parent (get (nth canonical index) "operation")]
      {:operationCid cid :previousCid (get operation "prev") :remoteCid (:head audit)
       :nullifiedCids cids :signer (signer! (get (normalize parent) "rotationKeys") operation)
       :expiresAt (str (.plus (Instant/parse (get (first discarded) "createdAt")) recovery-window))
       :directoryData (:data verified) :entries proposed})))

(defn pending-disposition!
  "Classify a queued signed operation against a fully verified audit. Superseded
  means a delayed submission cannot replace the observed history. Never infer
  cancellation from elapsed wall time, a rejected POST, or a missing parent."
  [did entries operation]
  (let [audit (verify-audit! did entries)
        cid (operation-cid operation)
        seen (into #{} (map #(get % "cid")) entries)
        parent (get operation "prev")
        canonical (filterv #(not (get % "nullified")) entries)
        index (first (keep-indexed #(when (= parent (get %2 "cid")) %1) canonical))]
    (cond
      (contains? seen cid) (if (contains? (:nullified audit) cid) :superseded :accepted)
      (contains? (:nullified audit) parent) :superseded
      (nil? index) :unresolved
      :else
      (let [previous (get (nth canonical index) "operation")
            keys (get (normalize previous) "rotationKeys")
            signer (signer! keys operation)
            child (get-in canonical [(inc index) "operation"])]
        (if (and child
                 (>= (.indexOf ^java.util.List keys signer)
                     (.indexOf ^java.util.List keys (signer! keys child))))
          :superseded
          :unresolved)))))
