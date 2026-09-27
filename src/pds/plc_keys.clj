(ns pds.plc-keys
  "Operator-authorized PLC control-key rotation through the durable identity queue."
  (:require [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.protocol.codec :as codec]
            [pds.protocol.formats :as formats]))

(defn identifiers! [did expected]
  (when-not (and (string? did) (re-matches #"did:plc:[a-z2-7]{24}" did))
    (errors/invalid! "Expected a PLC DID"))
  (when (and expected (not (formats/cid? expected))) (errors/invalid! "Expected a valid operation CID")))
(defn- public-key [bytes] (plc/did-key {:algorithm "ES256K" :public bytes}))
(defn- mismatch! [] (errors/raise! 409 "IdentityMismatch" "Identity changed; inspect current state before rotating"))

(defn- snapshot! [conn did]
  (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" did))
        identity (first (db/query conn "SELECT * FROM plc_identities WHERE did = ? AND status = 'ready'" did))]
    (when-not (and account (#{"active" "deactivated" "taken_down"} (:status account)))
      (errors/raise! 400 "AccountNotFound" "An established local account is required"))
    (when-not identity (errors/raise! 400 "UnsupportedDID" "A confirmed managed PLC identity is required"))
    {:account account :identity identity
     :repo (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" did))
     :pending (first (db/query conn "SELECT * FROM handle_updates WHERE did = ?" did))}))

(defn- receipt [conn did expected]
  (first (db/query conn "SELECT operation_cid, rotation_public FROM plc_key_rotations WHERE did = ? AND previous_cid = ?" did expected)))
(defn- result [expected row state]
  {:previousCid expected :operationCid (:operation_cid row) :state state
   :rotationKey (public-key (or (:next_rotation_public row) (:rotation_public row)))})

(defn status! [ds did]
  (identifiers! did nil)
  (db/transact! ds
    (fn [conn]
      (let [{:keys [identity pending]} (snapshot! conn did)]
        (cond-> {:did did :operationCid (:operation_cid identity) :rotationKey (public-key (:rotation_public identity))
                 :state (or (:status pending) "ready")}
          pending (assoc :pending {:kind (:operation_kind pending) :operationCid (:operation_cid pending)
                                   :attempts (:attempts pending) :error (:last_error pending)}))))))

(defn result! [ds did expected]
  (db/transact! ds
    (fn [conn]
      (if-let [done (receipt conn did expected)] (result expected done "completed")
        (let [job (first (db/query conn "SELECT * FROM handle_updates WHERE did = ? AND operation_kind = 'rotate'" did))]
          (when-not (and job (= expected (get (codec/decode (:operation job)) "prev"))) (mismatch!))
          (assoc (result expected job (:status job)) :error (:last_error job)))))))

(defn- existing! [conn did expected {:keys [identity pending]}]
  (if (receipt conn did expected) true
    (if pending
      (do
        (when-not (and (= "rotate" (:operation_kind pending))
                       (= expected (get (codec/decode (:operation pending)) "prev")))
          (errors/raise! 409 "IdentityUpdatePending" "Finish the pending identity operation first"))
        ;; An explicit operator retry can retry failure/backoff, but never steals
        ;; an unexpired lease or generates replacement key material.
        (db/execute! conn "UPDATE handle_updates SET status = 'pending', available_at = now(), last_error = NULL
                            WHERE did = ? AND status IN ('pending', 'failed')" did)
        true)
      (do (when-not (= expected (:operation_cid identity)) (mismatch!)) false))))

(defn- prepare [settings did expected {:keys [account identity repo]}]
  (let [audit (directory/audit! (:http-client settings) (:directory_url identity) did)
        data (:data audit) old (public-key (:rotation_public identity))]
    (when-not (and data (= expected (:head audit)) (= 1 (count (filter #{old} (get data "rotationKeys"))))
                   (= (plc/did-key {:algorithm "ES256" :public (:public_key repo)}) (get-in data ["verificationMethods" "atproto"]))
                   (= "AtprotoPersonalDataServer" (get-in data ["services" "atproto_pds" "type"]))
                   (= (:public-url settings) (get-in data ["services" "atproto_pds" "endpoint"]))
                   (= (str "at://" (:handle account)) (first (get data "alsoKnownAs"))))
      (mismatch!))
    (let [next (crypto/keypair "ES256K") purpose (str did ":plc-rotation")
          signer {:algorithm "ES256K" :private (crypto/unseal (:master-key settings) purpose (:rotation_key identity))}
          operation (plc/sign-operation (assoc (dissoc data "did") "type" "plc_operation" "prev" expected
                                              "rotationKeys" (mapv #(if (= old %) (plc/did-key next) %) (get data "rotationKeys"))) signer)]
      (when-not (= old (plc/signer! [old] operation)) (mismatch!))
      {:operation operation :sealed (crypto/seal (:master-key settings) purpose (:private next)) :public (:public next)})))

(defn enqueue!
  "Compare-and-swap an expected local and verified directory head. Network I/O
  holds no transaction. Concurrent retries converge on the stored signed bytes."
  [ds settings did expected]
  (identifiers! did expected)
  (when-not expected (errors/invalid! "An expected operation CID is required"))
  (when-let [snapshot (db/transact! ds
                       (fn [conn]
                         (let [snapshot (snapshot! conn did)]
                           (when-not (existing! conn did expected snapshot) snapshot))))]
    (let [{:keys [operation sealed public]} (prepare settings did expected snapshot)]
      (db/transact! ds
        (fn [conn]
          (let [{:keys [account identity repo] :as current} (snapshot! conn did)]
            (when-not (existing! conn did expected current)
              (when-not (and (= (:handle (:account snapshot)) (:handle account))
                             (= (:directory_url (:identity snapshot)) (:directory_url identity))
                             (= (vec (:rotation_public (:identity snapshot))) (vec (:rotation_public identity)))
                             (= (vec (:public_key (:repo snapshot))) (vec (:public_key repo))))
                (mismatch!))
              (db/execute! conn "INSERT INTO handle_updates(did, target_handle, external_handle, operation, operation_cid,
                                                          directory_url, operation_kind, next_rotation_key, next_rotation_public)
                                  VALUES (?, ?, false, ?, ?, ?, 'rotate', ?, ?)"
                           did (:handle account) (codec/encode operation) (plc/operation-cid operation)
                           (:directory_url identity) sealed public)))))))
  (result! ds did expected))

(defn validate-job!
  "Check persisted replacement material before any directory submission."
  [settings job operation]
  (when (= "rotate" (:operation_kind job))
    (try
      (let [key (:next_rotation_public job)
            private (crypto/unseal (:master-key settings) (str (:did job) ":plc-rotation") (:next_rotation_key job))
            challenge (codec/utf8 "pds/plc-rotation-key-check/v1")]
        (when-not (and (some #{(public-key key)} (get operation "rotationKeys"))
                       (crypto/verify "ES256K" key challenge (crypto/sign "ES256K" private challenge)))
          (throw (ex-info "Key mismatch" {}))))
      (catch Exception _ (throw (ex-info "Stored rotation key is invalid" {:retryable false}))))))

(defn install!
  "Called only by the fenced queue owner after directory confirmation, in the
  same account transaction as the operation snapshot and identity event."
  [conn job]
  (when (= "rotate" (:operation_kind job))
    (db/execute! conn "UPDATE plc_identities SET rotation_key = ?, rotation_public = ? WHERE did = ?"
                 (:next_rotation_key job) (:next_rotation_public job) (:did job))
    (db/execute! conn "INSERT INTO plc_key_rotations(did, previous_cid, operation_cid, rotation_public) VALUES (?, ?, ?, ?)"
                 (:did job) (get (codec/decode (:operation job)) "prev") (:operation_cid job) (:next_rotation_public job))))
