(ns pds.plc-reconcile-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.events :as events]
            [pds.handles :as handles]
            [pds.handles-test :as ht]
            [pds.identity-admin :as cli]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as tls]
            [pds.plc-keys :as control]
            [pds.plc-keys-test :as ct]
            [pds.plc-provision-test :as provision]
            [pds.plc-reconcile :as reconcile]
            [pds.plc-test :as ops]
            [pds.protocol.codec :as codec]
            [pds.protocol.repository :as repository]
            [pds.repo :as repo]
            [pds.signing-keys :as signing]
            [pds.signing-keys-test :as st]))

(use-fixtures :each fixture/isolated-database)
(defn tx [f] (db/transact! fixture/*ds* f))
(defn inspect [settings did] (reconcile/inspect! fixture/*ds* settings did))
(defn adopt [settings {:keys [did localCid queuedCid remoteCid]}]
  (cli/execute! fixture/*ds* settings (cli/command! ["reconcile-plc" did localCid queuedCid remoteCid])))
(defn external! [settings did op]
  (directory/ensure-operation! (:http-client settings) (:plc-url settings) did op))
(defn repo-bytes [did] (update-vals (st/state did) #(if (bytes? %) (vec %) %)))
(defn event-types [did] (mapv :event_type (provision/rows "SELECT event_type FROM repo_events WHERE did = ? ORDER BY seq" did)))

(deftest adopts-external-head-and-handle-with-retry-safe-receipt
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) alice (st/seed! settings) did (:did alice)
            old (ct/stored did) key (ct/old-key settings did old) before (repo-bytes did)
            op (ops/update-op (codec/decode (:operation old)) key {"alsoKnownAs" ["at://renamed.example.com" "https://profile.example.com"]})]
        (external! settings did op)
        (let [review (inspect (dissoc settings :master-key) did) result (adopt settings review)
              count-before (count (ht/changes did))]
          (is (= "-" (:queuedCid review)))
          (is (= "completed" (:state result)))
          (is (= "renamed.example.com" (ht/current-handle did)))
          (is (= (:remoteCid review) (:operation_cid (ct/stored did))))
          (is (= before (repo-bytes did)))
          (is (= [did] (mapv :did (provision/rows "SELECT did FROM handle_reservations WHERE handle = 'renamed.example.com'"))))
          (is (empty? (provision/rows "SELECT * FROM handle_reservations WHERE handle = 'alice.example.com'")))
          (is (= did (:did (tx #(auth/authenticate! % settings (ht/request alice))))))
          ;; Later local work cannot be undone by retrying an old reconciliation.
          (handles/update! fixture/*ds* settings (ht/request alice) {"handle" "later.example.com"})
          (is (= result (adopt settings review)))
          (is (= "later.example.com" (ht/current-handle did)))
          (is (= (inc count-before) (count (ht/changes did))))
          (is (= 1 (count (provision/rows "SELECT * FROM plc_reconciliations"))))
          (is (= "pds" (:database_role (first (provision/rows "SELECT database_role FROM plc_reconciliations")))))
          (is (= 3 (count (tls/posts calls))) "Reconciliation itself submits nothing"))))))

(deftest superseded-handle-job-releases-both-obsolete-reservations
  (tls/with-directory
    (fn [{:keys [client origin mode]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            old (ct/stored did) key (ct/old-key settings did old)
            op (ops/update-op (codec/decode (:operation old)) key {"alsoKnownAs" ["at://outside.example.com"]})]
        (reset! mode :ignore)
        (is (= "IdentityUpdatePending" (ct/error #(handles/update! fixture/*ds* settings (ht/request alice) {"handle" "queued.example.com"}))))
        (reset! mode :accept)
        (external! settings did op)
        (let [review (inspect settings did)]
          (is (= "superseded" (:queuedDisposition review)))
          (is (= "completed" (:state (adopt settings review))))
          (is (nil? (ct/job did)))
          (is (= ["outside.example.com"] (mapv :handle (provision/rows "SELECT handle FROM handle_reservations WHERE did = ?" did))))
          (is (= "outside.example.com" (ht/current-handle did))))))))

(deftest accepted-control-rotation-followed-by-external-descendant-retains-new-private-key
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            old (ct/stored did) before (repo-bytes did)]
        (control/enqueue! fixture/*ds* settings did (:operation_cid old))
        (let [job (ct/job did) op (codec/decode (:operation job))
              key {:algorithm "ES256K" :public (:next_rotation_public job)
                   :private (crypto/unseal (:master-key settings) (str did ":plc-rotation") (:next_rotation_key job))}
              next (ops/update-op op key {"alsoKnownAs" ["at://descendant.example.com"]})]
          (external! settings did op)
          (external! settings did next)
          (is (= :pending (handles/process-one! fixture/*ds* settings did)))
          (let [review (inspect settings did) result (adopt settings review)]
            (is (= "accepted" (:queuedDisposition result)))
            (is (= "queued" (:controlSource result)))
            (is (= (vec (:next_rotation_key job)) (vec (:rotation_key (ct/stored did)))))
            (is (= (:remoteCid review) (:operationCid (control/result! fixture/*ds* did (:operation_cid old)))))
            (is (= before (repo-bytes did)))
            (is (nil? (ct/job did)))
            (handles/update! fixture/*ds* settings (ht/request alice) {"handle" "working.example.com"})
            (is (= "working.example.com" (ht/current-handle did)))))))))

(deftest accepted-signing-descendant-resigns-repository-and-emits-correct-handle
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (st/seed! settings) did (:did alice)
            old (ct/stored did) key (ct/old-key settings did old) before (st/state did)
            record-rows (provision/rows "SELECT * FROM records WHERE did = ?" did)
            expected (signing/public-key (:public_key before))]
        (signing/enqueue! fixture/*ds* settings did expected)
        (let [job (ct/job did) op (codec/decode (:operation job))
              next (ops/update-op op key {"alsoKnownAs" ["at://signed.example.com"]})]
          (external! settings did op)
          (external! settings did next)
          (is (= "SigningKeyRotationPending" (ct/error #(tx (fn [c] (repo/state c did))))))
          (let [review (inspect settings did) result (adopt settings review) after (st/state did)
                exported (tx #(repo/export-car % did))]
            (is (= "queued" (:signingSource result)))
            (is (= (vec (:next_signing_key job)) (vec (:signing_key after))))
            (is (pos? (compare (:rev after) (:rev before))))
            (is (= record-rows (provision/rows "SELECT * FROM records WHERE did = ?" did)))
            (is (= (:head after) (:head (repository/verify-car exported did {:algorithm "ES256" :public (:public_key after)}))))
            (is (= ["identity" "sync"] (take-last 2 (event-types did))))
            (is (= "signed.example.com" (get (codec/decode (:payload (last (ht/changes did)))) "handle")))
            (is (= (:remoteCid review) (:operationCid (signing/result! fixture/*ds* did expected))))
            (is (= result (adopt settings review)))
            (is (nil? (ct/job did)))))))))

(deftest superseded-signing-job-keeps-old-key-and-restores-stream-checkpoint
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (st/seed! settings) did (:did alice)
            old (ct/stored did) before (repo-bytes did) key (ct/old-key settings did old)
            op (ops/update-op (codec/decode (:operation old)) key {"alsoKnownAs" ["at://retained.example.com"]})]
        (signing/enqueue! fixture/*ds* settings did (signing/public-key (:public_key (st/state did))))
        (external! settings did op)
        (let [review (inspect settings did) result (adopt settings review)]
          (is (= "superseded" (:queuedDisposition result)))
          (is (= "current" (:signingSource result)))
          (is (= before (repo-bytes did)))
          (is (= ["identity" "sync"] (take-last 2 (event-types did))))
          (is (empty? (provision/rows "SELECT * FROM signing_key_rotations")))
          (is (= (:head before) (:head (tx #(repo/state % did))))))))))

(deftest ambiguous-job-and-incompatible-directory-states-preserve-all-local-material
  (tls/with-directory
    (fn [{:keys [client origin logs audit-body]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            old (ct/stored did) key (ct/old-key settings did old) genesis (codec/decode (:operation old))
            base (get @logs did)]
        (control/enqueue! fixture/*ds* settings did (:operation_cid old))
        (let [saved (ct/job did) review (inspect settings did)]
          (is (= "unresolved" (:queuedDisposition review)))
          (is (= "IdentityOperationUnresolved" (:blockedBy review)))
          (is (= "IdentityOperationUnresolved" (ct/error #(adopt settings review))))
          (doseq [[changes expected] [[{"services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" "https://elsewhere.example.com"}}} "IdentityMoved"]
                                     [{"verificationMethods" {"atproto" (plc/did-key (crypto/keypair "ES256"))}} "UnknownSigningKey"]
                                     [{"rotationKeys" [(plc/did-key (crypto/keypair "ES256K"))]} "UnknownRotationKey"]]]
            (let [op (ops/update-op genesis key changes)]
              (swap! logs assoc did (conj base (ops/row did op (str (java.time.Instant/now)) false)))
              (let [review (inspect settings did)]
                (is (= expected (:blockedBy review)))
                (is (= expected (ct/error #(adopt settings review)))))))
          (swap! logs assoc did (conj base (ops/row did (ops/tombstone genesis key) (str (java.time.Instant/now)) false)))
          (let [review (inspect settings did)]
            (is (= "IdentityUnavailable" (:blockedBy review)))
            (is (= "IdentityUnavailable" (ct/error #(adopt settings review)))))
          (reset! audit-body "[]")
          (is (= :invalid-audit (ct/error #(inspect settings did))))
          (is (= (:operation_cid old) (:operation_cid (ct/stored did))))
          (is (= (vec (:next_rotation_key saved)) (vec (:next_rotation_key (ct/job did)))))
          (is (empty? (provision/rows "SELECT * FROM plc_reconciliations"))))))))

(deftest local-rollback-and-stale-worker-cannot-undo-reconciliation
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            old (ct/stored did) before (repo-bytes did)]
        (signing/enqueue! fixture/*ds* settings did (signing/public-key (:public_key (st/state did))))
        (let [saved (ct/job did) op (codec/decode (:operation saved))]
          (external! settings did op)
          (let [review (inspect settings did)]
            (with-redefs [events/append! (fn [& _] (throw (ex-info "Rollback" {})))]
              (is (thrown? Exception (adopt settings review))))
            (is (= before (repo-bytes did)))
            (is (= (:operation_cid old) (:operation_cid (ct/stored did))))
            (is (some? (ct/job did)))
            (is (empty? (provision/rows "SELECT * FROM signing_key_rotations")))
            (is (empty? (provision/rows "SELECT * FROM plc_reconciliations")))
            (let [ensure! directory/ensure-operation!]
              (with-redefs [directory/ensure-operation!
                            (fn [& args]
                              (let [result (apply ensure! args)]
                                (is (= "completed" (:state (adopt settings review))))
                                result))]
                (is (nil? (handles/process-one! fixture/*ds* settings did)) "Deleted queue row fences a worker with a successful stale outcome")))
            (is (= 1 (count (provision/rows "SELECT * FROM signing_key_rotations"))))
            (is (= 1 (count (provision/rows "SELECT * FROM plc_reconciliations"))))
            (is (= ["identity" "sync"] (take-last 2 (event-types did))))))))))

(deftest expected-cids-and-custom-handle-proof-are-rechecked
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            old (ct/stored did) key (ct/old-key settings did old)
            op (ops/update-op (codec/decode (:operation old)) key {"alsoKnownAs" ["at://custom.example.net"]})]
        (external! settings did op)
        (let [review (inspect settings did) bad (codec/cid (byte-array [99]))]
          (doseq [field [:localCid :queuedCid :remoteCid]]
            (is (= "IdentityMismatch" (ct/error #(adopt settings (assoc review field bad))))))
          (is (= "InvalidHandle" (ct/error #(adopt (assoc settings :txt-lookup (constantly ["did=did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"])) review))))
          (is (= "alice.example.com" (ht/current-handle did)))
          (let [new-op (ops/update-op op key {})]
            (is (= "IdentityMismatch"
                   (ct/error #(adopt (assoc settings :txt-lookup (fn [_] (external! settings did new-op) [(str "did=" did)])) review)))))
          (is (= (:operation_cid old) (:operation_cid (ct/stored did))))
          (let [review (inspect settings did)]
            (is (= "completed" (:state (adopt (assoc settings :txt-lookup (constantly [(str "did=" did)])) review)))))
          (is (= "custom.example.net" (ht/current-handle did))))))))

(deftest recovery-fork-can-retain-a-queued-signing-key-without-reactivating-account
  (tls/with-directory
    (fn [{:keys [client origin logs]}]
      (let [settings (provision/settings client origin)]
        (doseq [status ["deactivated" "taken_down"]]
          (let [recovery (crypto/keypair "ES256")
                handle (if (= status "deactivated") "alice.example.com" "bob.example.com")
                alice (accounts/create! fixture/*ds* settings (assoc (provision/signup) "handle" handle "email" (str status "@example.com")
                                                                    "recoveryKey" (plc/did-key recovery)))
                did (:did alice) old (ct/stored did) genesis (codec/decode (:operation old))]
            (tx #(db/execute! % "UPDATE accounts SET status = ? WHERE did = ?" status did))
            (signing/enqueue! fixture/*ds* settings did (signing/public-key (:public_key (st/state did))))
            (let [job (ct/job did) queued (codec/decode (:operation job))
                  recovered (ops/update-op genesis recovery {"verificationMethods" (get queued "verificationMethods")})]
              ;; The externally accepted recovery nullifies the queued operation
              ;; while deliberately retaining its new repository signing key.
              (swap! logs assoc did [(ops/row did genesis "2026-01-01T00:00:00Z" false)
                                    (ops/row did queued "2026-01-01T01:00:00Z" true)
                                    (ops/row did recovered "2026-01-01T02:00:00Z" false)])
              (let [review (inspect settings did) result (adopt settings review)]
                (is (= "superseded" (:queuedDisposition result)))
                (is (= "queued" (:signingSource result)))
                (is (= status (:status (first (provision/rows "SELECT status FROM accounts WHERE did = ?" did)))))
                (is (= (vec (:next_signing_key job)) (vec (:signing_key (st/state did)))))
                (is (empty? (filter #{"sync"} (event-types did))))
                (is (= "identity" (last (event-types did))))
                (is (= (:remoteCid review) (:operation_cid (ct/stored did))))))))))))

(deftest queued-higher-priority-operation-remains-unsafe-to-discard
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            old (ct/stored did) server (ct/old-key settings did old) lower (crypto/keypair "ES256")
            base (ops/update-op (codec/decode (:operation old)) server {"rotationKeys" [(plc/did-key server) (plc/did-key lower)]})]
        (external! settings did base)
        (adopt settings (inspect settings did))
        (control/enqueue! fixture/*ds* settings did (plc/operation-cid base))
        (let [saved (ct/job did) remote (ops/update-op base lower {"alsoKnownAs" ["at://lower.example.com"]})]
          (external! settings did remote)
          (is (= :pending (handles/process-one! fixture/*ds* settings did)))
          (let [review (inspect settings did)]
            (is (= "unresolved" (:queuedDisposition review)))
            (is (= "IdentityOperationUnresolved" (ct/error #(adopt settings review))))
            (is (= (:operation_cid saved) (:operation_cid (ct/job did))))
            (is (= (vec (:next_rotation_key saved)) (vec (:next_rotation_key (ct/job did)))))
            (is (= (plc/operation-cid base) (:operation_cid (ct/stored did))))))))))

(deftest corrupt-required-key-and-handle-collision-roll-back-adoption
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            bob (accounts/create! fixture/*ds* settings (assoc (provision/signup) "handle" "bob.example.com" "email" "bob@example.com"))
            old (ct/stored did) key (ct/old-key settings did old)]
        (signing/enqueue! fixture/*ds* settings did (signing/public-key (:public_key (st/state did))))
        (let [job (ct/job did) queued (codec/decode (:operation job))
              remote (ops/update-op queued key {"alsoKnownAs" ["at://bob.example.com"]})]
          (external! settings did queued)
          (external! settings did remote)
          (let [review (inspect settings did)]
            (tx #(db/execute! % "UPDATE handle_updates SET next_signing_key = ? WHERE did = ?" (byte-array [1 2 3]) did))
            (is (= "InvalidIdentityKey" (ct/error #(adopt settings review))))
            (tx #(db/execute! % "UPDATE handle_updates SET next_signing_key = ? WHERE did = ?" (:next_signing_key job) did))
            (is (= "HandleNotAvailable" (ct/error #(adopt settings review))))
            (is (= (:did bob) (:did (first (provision/rows "SELECT did FROM handle_reservations WHERE handle = 'bob.example.com'")))))
            (is (= "alice.example.com" (ht/current-handle did)))
            (is (= (:operation_cid old) (:operation_cid (ct/stored did))))
            (is (some? (ct/job did)))
            (is (empty? (provision/rows "SELECT * FROM plc_reconciliations")))
            (is (empty? (provision/rows "SELECT * FROM signing_key_rotations")))))))))

(deftest concurrent-operators-return-one-receipt-and-one-event
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            old (ct/stored did) key (ct/old-key settings did old)
            remote (ops/update-op (codec/decode (:operation old)) key {"alsoKnownAs" ["at://concurrent.example.com"]})]
        (external! settings did remote)
        (let [review (inspect settings did) start (promise)
              jobs (mapv (fn [_] (future @start (adopt settings review))) (range 4))]
          (deliver start true)
          (let [results (mapv #(deref % 20000 :timeout) jobs)]
            (is (not-any? #{:timeout} results))
            (is (= 1 (count (set results))))
            (is (= "completed" (:state (first results)))))
          (is (= 1 (count (provision/rows "SELECT * FROM plc_reconciliations"))))
          (is (= 2 (count (ht/changes did)))))))))

(deftest local-material-change-during-network-work-prevents-adoption
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            old (ct/stored did) key (ct/old-key settings did old)
            remote (ops/update-op (codec/decode (:operation old)) key {})]
        (external! settings did remote)
        (let [review (inspect settings did) audit! directory/audit!]
          (with-redefs [directory/audit!
                        (fn [& args]
                          (let [result (apply audit! args)]
                            (tx #(db/execute! % "UPDATE plc_identities SET directory_url = ? WHERE did = ?" "https://changed.example.com" did))
                            result))]
            (is (= "IdentityMismatch" (ct/error #(adopt settings review)))))
          (is (= (:operation_cid old) (:operation_cid (ct/stored did))))
          (is (empty? (provision/rows "SELECT * FROM plc_reconciliations"))))))))

(deftest cli-inspection-needs-no-master-key-and-mutation-uses-maintenance-lease
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            old (ct/stored did) key (ct/old-key settings did old)
            remote (ops/update-op (codec/decode (:operation old)) key {"alsoKnownAs" ["at://cli.example.com"]})
            env {"PDS_DATABASE_URL" (.getURL fixture/*ds*) "PDS_DATABASE_USER" (.getUser fixture/*ds*)
                 "PDS_DATABASE_PASSWORD" (.getPassword fixture/*ds*) "PDS_HOSTNAME" (:hostname settings)
                 "PDS_USER_DOMAIN" (:user-domain settings) "PDS_PUBLIC_URL" (:public-url settings)}
            audit! directory/audit!]
        (external! settings did remote)
        ;; Only replace transport trust for the real loopback TLS fixture; run!
        ;; still opens its pool, migrates and acquires the actual maintenance lease.
        (with-redefs [directory/audit! (fn [_ origin did] (audit! client origin did))]
          (let [{:keys [exit result]} (cli/run! ["inspect-plc" did] env)
                command ["reconcile-plc" did (:localCid result) (:queuedCid result) (:remoteCid result)]]
            (is (zero? exit))
            (is (= "cli.example.com" (get-in result [:plan :handle])))
            (is (= 1 (:exit (cli/run! command env))))
            (let [mutated (cli/run! command (assoc env "PDS_MASTER_KEY" (crypto/b64 (:master-key settings))))]
              (is (zero? (:exit mutated)))
              (is (= "completed" (get-in mutated [:result :state]))))
            (is (= "cli.example.com" (ht/current-handle did)))
            (is (= 1 (count (provision/rows "SELECT * FROM master_key_state"))))))))))
