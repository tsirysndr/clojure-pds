(ns pds.plc-recovery-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.handles :as handles]
            [pds.handles-test :as ht]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as tls]
            [pds.plc-keys :as control]
            [pds.plc-keys-test :as ct]
            [pds.plc-provision-test :as provision]
            [pds.plc-reconcile-test :as reconcile-test]
            [pds.plc-recovery :as recovery]
            [pds.plc-test :as ops]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.sync-api-test :as sync]))

(use-fixtures :each fixture/isolated-database)
(defn tx [f] (db/transact! fixture/*ds* f))

(deftest external-recovery-preserves-local-state-until-explicit-fenced-adoption
  (tls/with-directory
    (fn [{:keys [client origin logs]}]
      (let [settings (provision/settings client origin) key (crypto/keypair "ES256")
            alice (accounts/create! fixture/*ds* settings (assoc (provision/signup) "recoveryKey" (plc/did-key key)))
            did (:did alice) original (ct/stored did) genesis (codec/decode (:operation original))
            server-key (ct/old-key settings did original)]
        (tx #(repo/apply-writes! % settings did [{:action :create :collection "com.example.note" :rkey "retained"
                                                :value {"$type" "com.example.note" "text" "preserve this"}}] nil))
        (control/enqueue! fixture/*ds* settings did (:operation_cid original))
        (let [queued (ct/job did) before (reconcile-test/repo-bytes did)
              takeover (ops/update-op genesis server-key {"rotationKeys" [(plc/did-key (crypto/keypair "ES256K"))]
                                                         "alsoKnownAs" ["at://compromised.example.com"]})
              recovered (ops/update-op genesis key {"alsoKnownAs" ["at://restored.example.com"]})
              ensure! directory/ensure-operation!]
          (directory/ensure-operation! client origin did takeover)
          (is (= "UnknownRotationKey" (:blockedBy (reconcile-test/inspect settings did))))
          (with-redefs [directory/ensure-operation!
                        (fn [& args]
                          ;; Worker has an in-flight lease. Recovery uses only
                          ;; public signed bytes and does not edit its queue row.
                          (let [result (recovery/submit! client origin did (plc/operation-cid takeover) (plc/operation-cid recovered) recovered)]
                            (is (= "confirmed" (:state result)))
                            (is (= (:operation_cid original) (:operation_cid (ct/stored did))))
                            (is (= (vec (:next_rotation_key queued)) (vec (:next_rotation_key (ct/job did)))))
                            (let [adopted (reconcile-test/adopt settings (reconcile-test/inspect settings did))]
                              (is (= "superseded" (:queuedDisposition adopted)))
                              (is (= "current" (:controlSource adopted)))))
                          ;; A delayed old worker cannot reinstall or requeue its key.
                          (apply ensure! args))]
            (is (= :pending (handles/process-one! fixture/*ds* settings did))))
          (is (nil? (ct/job did)))
          (is (= (plc/operation-cid recovered) (:operation_cid (ct/stored did))))
          (is (= (vec (:rotation_key original)) (vec (:rotation_key (ct/stored did)))))
          (is (= "restored.example.com" (ht/current-handle did)))
          (is (= before (reconcile-test/repo-bytes did)))
          (is (empty? (provision/rows "SELECT * FROM plc_key_rotations")))
          (is (= 1 (count (provision/rows "SELECT * FROM plc_reconciliations"))))
          (sync/verify-upstream! "verify-recovery-submission.mjs"
                                [{:did did :entries (mapv #(assoc % "nullified" false) (butlast (get @logs did)))
                                  ;; Nullification flags in the pre-recovery snapshot.
                                  :operation recovered :now (get (peek (get @logs did)) "createdAt")
                                  :valid true :nullified [(plc/operation-cid takeover)]}]))))))

(deftest recovery-can-move-the-public-identity-without-mutating-the-former-pds
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) key (crypto/keypair "ES256K")
            did (:did (accounts/create! fixture/*ds* settings (assoc (provision/signup) "recoveryKey" (plc/did-key key))))
            original (ct/stored did) genesis (codec/decode (:operation original))
            child (ops/update-op genesis (ct/old-key settings did original) {"alsoKnownAs" ["at://intermediate.example.com"]})
            moved (ops/update-op genesis key {"rotationKeys" [(plc/did-key key)]
                                              "services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" "https://destination.example.com"}}})]
        (directory/ensure-operation! client origin did child)
        (is (= "confirmed" (:state (recovery/submit! client origin did (plc/operation-cid child) (plc/operation-cid moved) moved))))
        (is (= "IdentityMoved" (:blockedBy (reconcile-test/inspect settings did))))
        (is (= (:operation_cid original) (:operation_cid (ct/stored did))))
        (is (= "alice.example.com" (ht/current-handle did)))
        (is (empty? (provision/rows "SELECT * FROM plc_reconciliations")))))))
