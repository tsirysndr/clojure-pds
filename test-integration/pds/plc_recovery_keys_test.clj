(ns pds.plc-recovery-keys-test
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
            [pds.plc-reconcile-test :as rt]
            [pds.plc-recovery-keys :as recovery]
            [pds.plc-signing :as signing]
            [pds.plc-test :as ops]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.signing-keys-test :as st]
            [pds.sync-api-test :as sync]))

(use-fixtures :each fixture/isolated-database)
(defn set-keys [settings did expected keys]
  (cli/execute! fixture/*ds* settings (cli/command! (into ["set-recovery-keys" did expected] keys))))
(defn tx [f] (db/transact! fixture/*ds* f))
(defn cid [did] (:operation_cid (ct/stored did)))
(defn changes [] (provision/rows "SELECT * FROM plc_recovery_key_changes ORDER BY completed_at"))

(deftest recovery-key-replacement-reordering-and-removal-preserve-hosted-credentials
  (tls/with-directory
    (fn [{:keys [client origin calls logs]}]
      (let [settings (provision/settings client origin) old-recovery (crypto/keypair "ES256")
            alice (accounts/create! fixture/*ds* settings (assoc (provision/signup) "recoveryKey" (plc/did-key old-recovery)))
            did (:did alice) original (ct/stored did) genesis (codec/decode (:operation original))
            server (ct/old-key settings did original) repo-before (rt/repo-bytes did)
            keys (mapv #(plc/did-key (crypto/keypair %)) ["ES256" "ES256K" "ES256" "ES256K"])
            first-result (set-keys settings did (cid did) keys)
            first-op (codec/decode (:operation (ct/stored did)))
            second-result (set-keys settings did (cid did) (vec (reverse keys)))
            third-result (set-keys settings did (cid did) [])
            unchanged (set-keys settings did (cid did) [])]
        (is (= "completed" (:state first-result) (:state second-result) (:state third-result)))
        (is (= keys (:recoveryKeys first-result)))
        (is (= (vec (reverse keys)) (:recoveryKeys second-result)))
        (is (= [] (:recoveryKeys third-result)))
        (is (= "unchanged" (:state unchanged)))
        (is (= [(plc/did-key server)] (get-in (directory/audit! client origin did) [:data "rotationKeys"])))
        (is (= (dissoc (plc/operation-data did genesis) "rotationKeys")
               (dissoc (plc/operation-data did (codec/decode (:operation (ct/stored did)))) "rotationKeys")))
        (is (= repo-before (rt/repo-bytes did)))
        (is (= (vec (:rotation_key original)) (vec (:rotation_key (ct/stored did)))))
        (is (= 4 (count (ht/changes did))))
        (is (= 4 (count (tls/posts calls))))
        (is (= 3 (count (changes))))
        (is (= did (:did (tx #(auth/authenticate! % settings (ht/request alice))))))
        (is (= first-result (set-keys settings did (:operation_cid original) keys)) "Receipt survives later changes")
        (is (= "IdentityMismatch" (ct/error #(set-keys settings did (:operation_cid original) []))))
        ;; Removing a higher-priority key does not eliminate its 72-hour PLC
        ;; recovery authority over the lower-priority server's removal operation.
        (let [recovered (ops/update-op genesis old-recovery {})
              audit [(ops/row did genesis "2026-01-01T00:00:00Z" false)
                     (ops/row did first-op "2026-01-01T01:00:00Z" true)
                     (ops/row did recovered "2026-01-01T02:00:00Z" false)]]
          (is (= (plc/operation-cid recovered) (:head (plc/verify-audit! did audit))))
          (sync/verify-upstream! "verify-recovery-keys.mjs"
                                {:did did :operations (mapv #(get % "operation") (get @logs did))
                                 :expectedKeys [[(plc/did-key old-recovery) (plc/did-key server)]
                                                (conj keys (plc/did-key server))
                                                (conj (vec (reverse keys)) (plc/did-key server)) [(plc/did-key server)]]
                                 :audit audit :auditHead (plc/operation-cid recovered)}))))))

(deftest queued-recovery-list-is-immutable-and-survives-rejected-and-ambiguous-submissions
  (tls/with-directory
    (fn [{:keys [client origin mode]}]
      (let [settings (provision/settings client origin) alice (st/seed! settings) did (:did alice)
            expected (cid did) keys [(plc/did-key (crypto/keypair "ES256"))]]
        (reset! mode :ignore)
        (is (= "pending" (:state (set-keys settings did expected keys))))
        (let [job (ct/job did)]
          (is (= "recovery" (:operation_kind job)))
          (is (= "IdentityMismatch" (ct/error #(set-keys settings did expected []))))
          (is (= "IdentityUpdatePending" (ct/error #(control/enqueue! fixture/*ds* settings did expected))))
          (is (= "IdentityUpdatePending" (ct/error #(handles/update! fixture/*ds* settings (ht/request alice) {"handle" "other.example.com"}))))
          (is (= "IdentityUpdatePending" (ct/error #(signing/request-signature! fixture/*ds* settings (ht/request alice)))))
          (tx #(repo/apply-writes! % settings did [{:action :create :collection "com.example.note" :rkey "during"
                                                   :value {"$type" "com.example.note"}}] nil))
          (is (= 2 (count (provision/rows "SELECT * FROM records WHERE did = ?" did))))
          (reset! mode :reject)
          (is (= "failed" (:state (set-keys settings did expected keys))))
          (is (= (vec (:operation job)) (vec (:operation (ct/job did)))))
          (reset! mode :accept-error)
          (is (= "completed" (:state (set-keys settings did expected keys))))
          (is (= (:operation_cid job) (cid did)))
          (is (nil? (ct/job did)))
          (is (= 1 (count (changes)))))))))

(deftest recovery-list-finalization-rolls-back-and-fences-stale-workers
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            expected (cid did) keys [(plc/did-key (crypto/keypair "ES256K"))]]
        (recovery/enqueue! fixture/*ds* settings did expected keys)
        (with-redefs [events/append! (fn [& _] (throw (ex-info "Local rollback" {})))]
          (is (thrown? Exception (handles/process-one! fixture/*ds* settings did))))
        (is (= expected (cid did)))
        (is (empty? (changes)))
        (ht/due!)
        (let [ensure! directory/ensure-operation!]
          (with-redefs [directory/ensure-operation!
                        (fn [& args]
                          (let [result (apply ensure! args)]
                            (ht/due!)
                            (with-redefs [directory/ensure-operation! ensure!]
                              (is (= :updated (handles/process-one! fixture/*ds* settings did))))
                            result))]
            (is (nil? (handles/process-one! fixture/*ds* settings did)))))
        (is (= 1 (count (changes))))
        (is (= 2 (count (ht/changes did))))
        (is (= 2 (count (tls/posts calls))))
        (is (= "completed" (:state (set-keys settings did expected keys))))))))

(deftest invalid-and-stale-recovery-requests-cannot-publish-or-consume-current-head
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            identity (ct/stored did) expected (cid did) server (plc/did-key (ct/old-key settings did identity))
            public (plc/did-key (crypto/keypair "ES256"))]
        (doseq [keys [[server] [public public] ["invalid"] (vec (repeat 5 public))]]
          (is (= "InvalidRequest" (ct/error #(recovery/enqueue! fixture/*ds* settings did expected keys)))))
        (is (= "IdentityMismatch" (ct/error #(set-keys settings did (codec/cid (byte-array [9])) [public]))))
        (is (= "unchanged" (:state (set-keys settings did expected []))))
        (is (empty? (changes)))
        (is (nil? (ct/job did)))
        (is (= 1 (count (tls/posts calls))))
        ;; A no-op creates no receipt that would prevent a real change at this CID.
        (is (= "completed" (:state (set-keys settings did expected [public]))))))))

(deftest externally-changed-identity-and-local-races-require-new-inspection
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            old (ct/stored did) expected (cid did) key (ct/old-key settings did old)
            public (plc/did-key (crypto/keypair "ES256"))
            remote (ops/update-op (codec/decode (:operation old)) key {})]
        (directory/ensure-operation! client origin did remote)
        (is (= "IdentityMismatch" (ct/error #(set-keys settings did expected [public]))))
        (is (nil? (ct/job did)))
        (rt/adopt settings (rt/inspect settings did))
        (let [audit! directory/audit! expected (cid did)]
          (with-redefs [directory/audit!
                        (fn [& args]
                          (let [result (apply audit! args)]
                            (tx #(db/execute! % "UPDATE plc_identities SET directory_url = 'https://changed.example.com' WHERE did = ?" did))
                            result))]
            (is (= "IdentityMismatch" (ct/error #(set-keys settings did expected [public])))))
          (is (nil? (ct/job did)))
          (is (empty? (changes))))))))

(deftest concurrent-recovery-key-requests-share-one-operation
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            expected (cid did) keys [(plc/did-key (crypto/keypair "ES256"))] start (promise)
            jobs (mapv (fn [_] (future @start (recovery/enqueue! fixture/*ds* settings did expected keys))) (range 4))]
        (deliver start true)
        (let [results (mapv #(deref % 20000 :timeout) jobs)]
          (is (not-any? #{:timeout} results))
          (is (= 1 (count (set (map :operationCid results))))))
        (is (= :updated (handles/process-one! fixture/*ds* settings did)))
        (is (= 1 (count (changes))))
        (is (= 2 (count (tls/posts calls))))))))

(deftest recovery-key-changes-preserve-inactive-status
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)]
        (doseq [status ["deactivated" "taken_down"]]
          (tx #(db/execute! % "UPDATE accounts SET status = ? WHERE did = ?" status did))
          (is (= "completed" (:state (set-keys settings did (cid did) [(plc/did-key (crypto/keypair "ES256"))]))))
          (is (= status (:status (first (provision/rows "SELECT status FROM accounts WHERE did = ?" did)))))
          (is (empty? (filter #{"sync"} (rt/event-types did)))))))))

(deftest reconciliation-receipts-require-the-requested-complete-key-list
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin)]
        (doseq [accepted? [true false]]
          (let [handle (if accepted? "alice.example.com" "bob.example.com")
                alice (accounts/create! fixture/*ds* settings (assoc (provision/signup) "handle" handle "email" (str handle "@example.com")))
                did (:did alice) original (ct/stored did) expected (cid did) key (ct/old-key settings did original)
                recovery-key (crypto/keypair "ES256") requested [(plc/did-key recovery-key)]]
            (recovery/enqueue! fixture/*ds* settings did expected requested)
            (let [job (ct/job did) queued (codec/decode (:operation job))
                  base (if accepted? queued (codec/decode (:operation original)))
                  remote (ops/update-op base (if accepted? recovery-key key) {"alsoKnownAs" [(str "at://" handle) "https://profile.example.com"]})]
              (when accepted? (directory/ensure-operation! client origin did queued))
              (directory/ensure-operation! client origin did remote)
              (is (= :pending (handles/process-one! fixture/*ds* settings did)))
              (rt/adopt settings (rt/inspect settings did))
              (is (nil? (ct/job did)))
              (if accepted?
                (let [result (set-keys settings did expected requested)]
                  (is (= "completed" (:state result)))
                  (is (= (plc/operation-cid remote) (:operationCid result)))
                  (is (= requested (:recoveryKeys result))))
                (do
                  (is (empty? (provision/rows "SELECT * FROM plc_recovery_key_changes WHERE did = ?" did)))
                  (is (= "IdentityMismatch" (ct/error #(set-keys settings did expected requested))))
                  (is (= "completed" (:state (set-keys settings did (cid did) requested)))))))))))))
