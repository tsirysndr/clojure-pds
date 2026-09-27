(ns pds.plc-recovery-keys
  "Public recovery-key replacement. Account-held private keys never enter this PDS."
  (:require [clojure.data.json :as json]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.plc :as plc]
            [pds.plc-keys :as control]
            [pds.protocol.codec :as codec]))

(defn identifiers! [did expected recovery-keys]
  (control/identifiers! did expected)
  (when-not expected (errors/invalid! "An expected operation CID is required"))
  (when-not (and (vector? recovery-keys) (<= (count recovery-keys) 4)
                 (= (count recovery-keys) (count (set recovery-keys)))
                 (every? #(try (plc/parse-key %) true (catch Exception _ false)) recovery-keys))
    (errors/invalid! "Provide zero to four distinct P-256 or secp256k1 recovery did:key values in priority order")))
(defn- mismatch! []
  (errors/raise! 409 "IdentityMismatch" "Identity or requested recovery keys changed; inspect current state"))
(defn- server-key [identity] (plc/did-key {:algorithm "ES256K" :public (:rotation_public identity)}))
(defn- snapshot! [conn did]
  (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" did))
        identity (first (db/query conn "SELECT * FROM plc_identities WHERE did = ? AND status = 'ready'" did))
        repo (first (db/query conn "SELECT public_key, head FROM repositories WHERE did = ?" did))]
    (when-not (and (#{"active" "deactivated" "taken_down"} (:status account)) (:head repo))
      (errors/raise! 400 "AccountNotFound" "An established local repository is required"))
    (when-not identity (errors/raise! 400 "UnsupportedDID" "A confirmed managed PLC identity is required"))
    {:account account :identity identity :repo repo
     :pending (first (db/query conn "SELECT * FROM handle_updates WHERE did = ?" did))}))
(defn- version [{:keys [account identity repo]}]
  [(:handle account) (:operation_cid identity) (:directory_url identity)
   (vec (:rotation_key identity)) (vec (:rotation_public identity)) (vec (:public_key repo))])
(defn- receipt [conn did expected]
  (when-let [row (first (db/query conn "SELECT * FROM plc_recovery_key_changes WHERE did = ? AND previous_cid = ?" did expected))]
    {:previousCid expected :operationCid (:operation_cid row) :state "completed"
     :recoveryKeys (json/read-str (str (:recovery_keys row))) :rotationKey (:server_key row)}))
(defn- job-result [job]
  (let [operation (codec/decode (:operation job)) keys (get operation "rotationKeys")]
    {:previousCid (get operation "prev") :operationCid (:operation_cid job) :state (:status job)
     :recoveryKeys (pop keys) :rotationKey (peek keys) :error (:last_error job)}))
(defn- same-request! [result expected recovery-keys]
  (when-not (and (= expected (:previousCid result)) (= recovery-keys (:recoveryKeys result))) (mismatch!))
  result)

(defn result! [ds did expected recovery-keys]
  (db/transact! ds
    (fn [conn]
      (same-request!
        (or (receipt conn did expected)
            (when-let [job (first (db/query conn "SELECT * FROM handle_updates WHERE did = ? AND operation_kind = 'recovery'" did))]
              (job-result job))
            (mismatch!)) expected recovery-keys))))

(defn- existing! [conn did expected recovery-keys {:keys [identity pending]}]
  (if-let [done (receipt conn did expected)] (same-request! done expected recovery-keys)
    (if pending
      (do
        (when-not (= "recovery" (:operation_kind pending))
          (errors/raise! 409 "IdentityUpdatePending" "Finish the pending identity operation first"))
        (same-request! (job-result pending) expected recovery-keys)
        (db/execute! conn "UPDATE handle_updates SET status = 'pending', available_at = now(), last_error = NULL
                            WHERE did = ? AND status IN ('pending', 'failed')" did)
        true)
      (do (when-not (= expected (:operation_cid identity)) (mismatch!)) nil))))

(defn enqueue! [ds settings did expected recovery-keys]
  (identifiers! did expected recovery-keys)
  (let [snapshot (db/transact! ds
                   (fn [conn]
                     (let [snapshot (snapshot! conn did)]
                       (when-not (existing! conn did expected recovery-keys snapshot) snapshot))))]
    (if-not snapshot (result! ds did expected recovery-keys)
      (let [identity (:identity snapshot) server (server-key identity)
            _ (when (some #{server} recovery-keys) (errors/invalid! "The PDS control key is retained automatically; do not include it as a recovery key"))
            audit (control/verified-audit! settings did snapshot)
            ordered (conj recovery-keys server)
            unchanged? (= ordered (get-in audit [:data "rotationKeys"]))
            operation (when-not unchanged?
                        (let [private (crypto/unseal (:master-key settings) (str did ":plc-rotation") (:rotation_key identity))]
                          (try
                            (let [operation (plc/sign-operation (assoc (dissoc (:data audit) "did") "type" "plc_operation"
                                                                       "prev" expected "rotationKeys" ordered)
                                                                {:algorithm "ES256K" :private private})]
                              (plc/signer! [server] operation)
                              operation)
                            (finally (java.util.Arrays/fill ^bytes private (byte 0))))))
            noop (db/transact! ds
                   (fn [conn]
                     (let [{:keys [account identity] :as current} (snapshot! conn did)]
                       (when-not (existing! conn did expected recovery-keys current)
                         (when-not (= (version snapshot) (version current)) (mismatch!))
                         (if unchanged?
                           {:previousCid expected :operationCid expected :state "unchanged" :recoveryKeys recovery-keys :rotationKey server}
                           (do
                             (db/execute! conn "INSERT INTO handle_updates(did, target_handle, external_handle, operation, operation_cid, directory_url, operation_kind)
                                                 VALUES (?, ?, false, ?, ?, ?, 'recovery')"
                                          did (:handle account) (codec/encode operation) (plc/operation-cid operation) (:directory_url identity))
                             nil))))))]
        (or noop (result! ds did expected recovery-keys))))))

(defn install!
  "Record the confirmed key list in the fenced identity transaction. Reconciliation
  may pass a newer confirming CID only if its complete rotation-key list matches."
  [conn job]
  (when (= "recovery" (:operation_kind job))
    (let [operation (codec/decode (:operation job)) keys (get operation "rotationKeys")]
      (plc/signer! [(peek keys)] operation)
      (db/execute! conn "INSERT INTO plc_recovery_key_changes(did, previous_cid, operation_cid, recovery_keys, server_key) VALUES (?, ?, ?, ?::jsonb, ?)"
                   (:did job) (get operation "prev") (:operation_cid job) (json/write-str (pop keys)) (peek keys)))))
