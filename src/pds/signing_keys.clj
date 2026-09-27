(ns pds.signing-keys
  (:require [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.events :as events]
            [pds.protocol.syntax :as syntax]
            [pds.plc :as plc]
            [pds.plc-keys :as plc-keys]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]))

(defn identifiers! [did expected]
  (when-not (and (syntax/did? did) (or (re-matches #"did:plc:[a-z2-7]{24}" did)
                                                  (and (str/starts-with? did "did:web:") (syntax/handle? (subs did 8))))) (errors/invalid! "Expected a supported account DID"))
  (when expected
    (when-not (try (= "ES256" (:algorithm (plc/parse-key expected))) (catch Exception _ false))
      (errors/invalid! "Expected the current P-256 signing did:key"))))
(defn public-key [bytes] (plc/did-key {:algorithm "ES256" :public bytes}))
(defn- mismatch! [] (errors/raise! 409 "IdentityMismatch" "Identity or signing key changed; inspect current state"))
(defn- snapshot! [conn did]
  (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" did))
        plc? (str/starts-with? did "did:plc:")
        identity (when plc? (first (db/query conn "SELECT * FROM plc_identities WHERE did = ? AND status = 'ready'" did)))
        repo (first (db/query conn "SELECT * FROM repositories WHERE did = ?" did))]
    (when-not (and account (#{"active" "deactivated" "taken_down"} (:status account)) (:head repo))
      (errors/raise! 400 "AccountNotFound" "An established local repository is required"))
    (when (or (and plc? (nil? identity)) (and (not plc?) (:imported account)))
      (errors/raise! 400 "UnsupportedDID" "This PDS must manage the published signing key"))
    {:account account :identity identity :repo repo
     :pending (first (db/query conn "SELECT * FROM handle_updates WHERE did = ?" did))}))

(defn status! [ds did]
  (identifiers! did nil)
  (db/transact! ds
    (fn [conn]
      (let [{:keys [identity repo pending]} (snapshot! conn did)]
        (cond-> {:did did :signingKey (public-key (:public_key repo)) :repoCommit (:head repo) :repoRev (:rev repo)
                 :state (or (:status pending) "ready")}
          identity (assoc :operationCid (:operation_cid identity)
                          :rotationKey (plc/did-key {:algorithm "ES256K" :public (:rotation_public identity)}))
          pending (assoc :pending {:kind (:operation_kind pending) :operationCid (:operation_cid pending)
                                   :attempts (:attempts pending) :error (:last_error pending)}))))))
(defn- receipt [conn did expected]
  (first (db/query conn "SELECT * FROM signing_key_rotations WHERE did = ? AND previous_key = ?" did expected)))
(defn- result [row state]
  (cond-> {:previousKey (:previous_key row) :signingKey (public-key (:signing_public row)) :state state}
    (:operation_cid row) (assoc :operationCid (:operation_cid row))
    (:repo_commit row) (assoc :repoCommit (:repo_commit row) :repoRev (:repo_rev row))))
(defn result! [ds did expected]
  (db/transact! ds
    (fn [conn]
      (if-let [done (receipt conn did expected)] (result done "completed")
        (let [job (first (db/query conn "SELECT * FROM handle_updates WHERE did = ? AND operation_kind = 'signing' AND previous_signing_key = ?" did expected))]
          (when-not job (mismatch!))
          (assoc (result {:previous_key expected :signing_public (:next_signing_public job) :operation_cid (:operation_cid job)} (:status job))
                 :error (:last_error job)))))))

(defn- existing! [conn did expected {:keys [repo pending]}]
  (if (receipt conn did expected) true
    (if pending
      (do
        (when-not (and (= "signing" (:operation_kind pending)) (= expected (:previous_signing_key pending)))
          (errors/raise! 409 "IdentityUpdatePending" "Finish the pending identity operation first"))
        (db/execute! conn "UPDATE handle_updates SET status = 'pending', available_at = now(), last_error = NULL
                            WHERE did = ? AND status IN ('pending', 'failed')" did)
        true)
      (do (when-not (= expected (public-key (:public_key repo))) (mismatch!)) false))))

(defn- install-key! [conn settings account expected sealed public operation-cid]
  (let [did (:did account)
        current (:public_key (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" did)))]
    (when-not (= expected (public-key current)) (mismatch!))
    (let [commit (repo/resign! conn settings did sealed public)]
      (db/execute! conn "INSERT INTO signing_key_rotations(did, previous_key, signing_public, operation_cid, repo_commit, repo_rev)
                          VALUES (?, ?, ?, ?, ?, ?)" did expected public operation-cid (:cid commit) (:rev commit))
      (events/append! conn did "identity" {"did" did "handle" (:handle account)})
      (when (= "active" (:status account)) (events/sync! conn did)))))

(defn- new-key [settings did]
  (let [key (crypto/keypair "ES256")]
    {:sealed (crypto/seal (:master-key settings) did (:private key)) :public (:public key)}))
(defn- version [{:keys [account identity repo]}]
  [(:handle account) (:imported account) (:operation_cid identity) (:directory_url identity)
   (vec (:rotation_public identity)) (vec (:public_key repo))])

(defn enqueue! [ds settings did expected]
  (identifiers! did expected)
  (when-not expected (errors/invalid! "The expected signing public key is required"))
  (when-let [snapshot
             (db/transact! ds
               (fn [conn]
                 (let [{:keys [account identity] :as snapshot} (snapshot! conn did)]
                   (when-not (existing! conn did expected snapshot)
                     (if identity snapshot
                       (let [{:keys [sealed public]} (new-key settings did)]
                         (install-key! conn settings account expected sealed public nil)
                         nil))))))]
    (let [audit (plc-keys/verified-audit! settings did snapshot)
          {:keys [sealed public]} (new-key settings did)
          identity (:identity snapshot)
          signer {:algorithm "ES256K" :private (crypto/unseal (:master-key settings) (str did ":plc-rotation") (:rotation_key identity))}
          operation (plc/sign-operation (-> (:data audit) (dissoc "did")
                                           (assoc "type" "plc_operation" "prev" (:head audit))
                                           (assoc-in ["verificationMethods" "atproto"] (public-key public))) signer)]
      (plc/signer! [(plc/did-key {:algorithm "ES256K" :public (:rotation_public identity)})] operation)
      (db/transact! ds
        (fn [conn]
          (let [{:keys [account identity] :as current} (snapshot! conn did)]
            (when-not (existing! conn did expected current)
              (when-not (= (version snapshot) (version current)) (mismatch!))
              (db/execute! conn "INSERT INTO handle_updates(did, target_handle, external_handle, operation, operation_cid, directory_url,
                                                          operation_kind, previous_signing_key, next_signing_key, next_signing_public)
                                  VALUES (?, ?, false, ?, ?, ?, 'signing', ?, ?, ?)"
                           did (:handle account) (codec/encode operation) (plc/operation-cid operation)
                           (:directory_url identity) expected sealed public)))))))
  (result! ds did expected))

(defn validate-job! [settings job operation]
  (when (= "signing" (:operation_kind job))
    (try
      (let [public (:next_signing_public job) message (codec/utf8 "pds/signing-key-check/v1")
            private (crypto/unseal (:master-key settings) (:did job) (:next_signing_key job))]
        (when-not (and (= (public-key public) (get-in operation ["verificationMethods" "atproto"]))
                       (crypto/verify "ES256" public message (crypto/sign "ES256" private message)))
          (throw (ex-info "Invalid key" {}))))
      (catch Exception _ (throw (ex-info "Stored signing key is invalid" {:retryable false}))))))

(defn install! [conn settings account job]
  (when (= "signing" (:operation_kind job))
    (install-key! conn settings account (:previous_signing_key job) (:next_signing_key job)
                  (:next_signing_public job) (:operation_cid job))))
