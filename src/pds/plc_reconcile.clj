(ns pds.plc-reconcile
  "Explicit local adoption of verified PLC history; never submits recovery forks."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.events :as events]
            [pds.handle-registry :as registry]
            [pds.handles :as handles]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-keys :as keys]
            [pds.protocol.codec :as codec]
            [pds.signing-keys :as signing]))

(defn- mismatch! []
  (errors/raise! 409 "IdentityMismatch" "Identity changed; inspect local, queued and directory CIDs again"))
(defn identifiers! [did local queued remote]
  (keys/identifiers! did local)
  (when-not (and local queued remote) (errors/invalid! "Local, queued and remote CIDs are required; use - for no queued operation"))
  (when-not (= "-" queued) (keys/identifiers! did queued))
  (keys/identifiers! did remote))

(defn- snapshot! [conn did]
  (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" did))
        identity (first (db/query conn "SELECT * FROM plc_identities WHERE did = ? AND status = 'ready'" did))
        repo (first (db/query conn "SELECT * FROM repositories WHERE did = ?" did))]
    (when-not (and (#{"active" "deactivated" "taken_down"} (:status account)) (:head repo))
      (errors/raise! 400 "AccountNotFound" "An established local repository is required"))
    (when-not identity (errors/raise! 400 "UnsupportedDID" "A confirmed managed PLC identity is required"))
    {:account account :identity identity :repo repo
     :pending (first (db/query conn "SELECT * FROM handle_updates WHERE did = ? FOR UPDATE" did))}))

(defn- version [{:keys [account identity repo pending]}]
  ;; Ignore lease/backoff changes and ordinary repository writes. All material
  ;; used by the plan is checked again after network I/O, under account/job locks.
  (mapv #(update-vals % (fn [v] (if (bytes? v) (vec v) v)))
        [(select-keys account [:handle :imported])
         (select-keys identity [:operation_cid :directory_url :rotation_key :rotation_public])
         (select-keys repo [:signing_key :public_key])
         (select-keys pending [:operation :operation_cid :operation_kind :target_handle :directory_url
                              :next_rotation_key :next_rotation_public :next_signing_key :next_signing_public :previous_signing_key])]))
(defn- control-key [public] (plc/did-key {:algorithm "ES256K" :public public}))
(defn- queued-cid [snapshot] (or (get-in snapshot [:pending :operation_cid]) "-"))
(defn- expected! [snapshot local queued]
  (when-not (and (= local (get-in snapshot [:identity :operation_cid])) (= queued (queued-cid snapshot))) (mismatch!)))
(defn- audit! [settings did snapshot]
  (or (directory/audit! (:http-client settings) (get-in snapshot [:identity :directory_url]) did)
      (errors/raise! 409 "IdentityMismatch" "Directory identity is missing")))
(defn- disposition! [did audit pending]
  (if-not pending :none
    (let [operation (codec/decode (:operation pending))]
      (when-not (= (:operation_cid pending) (plc/operation-cid operation)) (mismatch!))
      (plc/pending-disposition! did (:entries audit) operation))))

(defn- plan! [settings {:keys [account identity repo pending]} audit disposition]
  (let [data (:data audit)
        _ (when-not data (errors/raise! 409 "IdentityUnavailable" "A tombstoned identity cannot be adopted"))
        _ (when-not (and (= "AtprotoPersonalDataServer" (get-in data ["services" "atproto_pds" "type"]))
                         (= (str/replace (:public-url settings) #"/$" "")
                            (str/replace (get-in data ["services" "atproto_pds" "endpoint"] "") #"/$" "")))
            (errors/raise! 409 "IdentityMoved" "Directory identity no longer points to this PDS"))
        alias (first (get data "alsoKnownAs"))
        handle (handles/normalize! settings (when (and (string? alias) (str/starts-with? alias "at://")) (subs alias 5)))
        remote-signing (get-in data ["verificationMethods" "atproto"])
        signing-source (cond (= remote-signing (signing/public-key (:public_key repo))) :current
                             (and (= "signing" (:operation_kind pending))
                                  (= remote-signing (signing/public-key (:next_signing_public pending)))) :queued
                             :else (errors/raise! 409 "UnknownSigningKey" "Directory signing key is not available locally"))
        controls (set (get data "rotationKeys"))
        control-source (cond (and (= "rotate" (:operation_kind pending))
                                 (controls (control-key (:next_rotation_public pending)))) :queued
                             (controls (control-key (:rotation_public identity))) :current
                             :else (errors/raise! 409 "UnknownRotationKey" "Directory control key is not available locally"))]
    (when (= :unresolved disposition)
      (errors/raise! 409 "IdentityOperationUnresolved" "Queued operation could still be accepted; preserve it and resolve directory history first"))
    {:handle handle :signingSource (name signing-source) :controlSource (name control-source)
     :queuedDisposition (name disposition)}))

(defn inspect! [ds settings did]
  (keys/identifiers! did nil)
  (let [snapshot (db/transact! ds #(snapshot! % did))
        audit (audit! settings did snapshot)
        disposition (disposition! did audit (:pending snapshot))
        eligibility (try {:plan (plan! settings snapshot audit disposition)}
                         (catch clojure.lang.ExceptionInfo e
                           (if (:xrpc (ex-data e)) {:blockedBy (:error (ex-data e))} (throw e))))]
    (merge {:did did :localCid (get-in snapshot [:identity :operation_cid])
            :queuedCid (queued-cid snapshot) :remoteCid (:head audit) :directoryData (:data audit)
            :queuedDisposition (name disposition)} eligibility)))

(defn- check-key! [settings context algorithm public sealed]
  (try
    (let [private (crypto/unseal (:master-key settings) context sealed)
          challenge (codec/utf8 "pds/plc-reconciliation/v1")]
      (try
        (when-not (crypto/verify algorithm public challenge (crypto/sign algorithm private challenge))
          (throw (ex-info "Key mismatch" {})))
        (finally (java.util.Arrays/fill ^bytes private (byte 0)))))
    (catch Exception _ (errors/raise! 409 "InvalidIdentityKey" "Required private key is unavailable or does not match its public key"))))

(defn- receipt [conn did local queued remote]
  (some-> (first (db/query conn "SELECT result FROM plc_reconciliations WHERE did = ? AND local_cid = ? AND queued_cid = ? AND remote_cid = ?"
                           did local queued remote))
          :result str (json/read-str :key-fn keyword)))

(defn reconcile! [ds settings did local queued remote]
  (identifiers! did local queued remote)
  (let [initial (db/transact! ds
                  (fn [conn]
                    (let [snapshot (snapshot! conn did)]
                      (if-let [done (receipt conn did local queued remote)] {:done done}
                        (do (expected! snapshot local queued) {:snapshot snapshot})))))]
    (or (:done initial)
        (let [{:keys [account identity repo pending] :as snapshot} (:snapshot initial)
              audit (audit! settings did snapshot)
              _ (when-not (= remote (:head audit)) (mismatch!))
              disposition (disposition! did audit pending)
              {:keys [handle signingSource controlSource] :as plan} (plan! settings snapshot audit disposition)
              signing? (= "queued" signingSource) control? (= "queued" controlSource)
              _ (check-key! settings did "ES256" (if signing? (:next_signing_public pending) (:public_key repo))
                            (if signing? (:next_signing_key pending) (:signing_key repo)))
              _ (check-key! settings (str did ":plc-rotation") "ES256K"
                            (if control? (:next_rotation_public pending) (:rotation_public identity))
                            (if control? (:next_rotation_key pending) (:rotation_key identity)))
              _ (when-not (handles/hosted? settings handle) (handles/verify-external! settings did handle))
              ;; Handle proof can take time. Require the reviewed head again just
              ;; before local adoption; PLC/SQL still cannot be one transaction.
              fresh (audit! settings did snapshot)
              _ (when-not (= remote (:head fresh)) (mismatch!))
              operation (get (last (:entries fresh)) "operation")]
          (db/transact! ds
            (fn [conn]
              (let [current (snapshot! conn did)]
                (if-let [done (receipt conn did local queued remote)] done
                  (do
                    (expected! current local queued)
                    (when-not (= (version snapshot) (version current)) (mismatch!))
                    (registry/reserve! conn did handle)
                    (db/execute! conn "UPDATE accounts SET handle = ? WHERE did = ?" handle did)
                    (db/execute! conn "UPDATE plc_identities SET operation = ?, operation_cid = ?, confirmed_at = now() WHERE did = ?"
                                 (codec/encode operation) remote did)
                    ;; Rotation receipts report the head that actually confirmed
                    ;; the installed key, including a descendant or recovery fork.
                    (when control? (keys/install! conn (assoc pending :operation_cid remote)))
                    (when signing? (signing/install! conn settings (assoc (:account current) :handle handle)
                                                    (assoc pending :operation_cid remote)))
                    (db/execute! conn "DELETE FROM handle_updates WHERE did = ?" did)
                    (doseq [old (distinct (remove #{nil handle} [(:handle account) (:target_handle pending)]))]
                      (registry/release! conn did old))
                    (when-not signing?
                      (events/append! conn did "identity" {"did" did "handle" handle})
                      ;; A canceled signing job also needs a checkpoint: clients
                      ;; may have advanced their cursor while commits were hidden.
                      (when (and (= "signing" (:operation_kind pending)) (= "active" (get-in current [:account :status])))
                        (events/sync! conn did)))
                    (let [result (merge plan {:did did :localCid local :queuedCid queued :remoteCid remote :state "completed"})]
                      (db/execute! conn "INSERT INTO plc_reconciliations(did, local_cid, queued_cid, remote_cid, result) VALUES (?, ?, ?, ?, ?::jsonb)"
                                   did local queued remote (json/write-str result))
                      result))))))))))
