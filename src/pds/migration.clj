(ns pds.migration
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.email :as email]
            [pds.errors :as errors]
            [pds.events :as events]
            [pds.handle-registry :as registry]
            [pds.handles :as handles]
            [pds.identity :as identity]
            [pds.invites :as invites]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.request :as request]
            [pds.reserved-keys :as reserved-keys]
            [pds.service-auth :as service-auth]))

(defn create! [ds settings resolver request body]
  (when-not (:signup-enabled settings) (errors/raise! 403 "SignupDisabled" "Account registration is disabled"))
  (when (some #(contains? body %) ["plcOp" "recoveryKey" "verificationCode" "verificationPhone"])
    (errors/invalid! "PLC creation fields and phone verification are not supported when importing a DID"))
  (let [did (get body "did") handle (handles/normalize! settings (get body "handle"))
        address (str/lower-case (request/string! (get body "email") "email"))]
    (when-not (identity/supported-did? did) (errors/raise! 400 "UnresolvableDid" "A supported existing DID is required"))
    (when-not (email/address? address) (errors/invalid! "Invalid email address"))
    (let [proof (service-auth/verify! resolver settings request "com.atproto.server.createAccount")]
      (when-not (= did (:did proof)) (errors/raise! 401 "AuthenticationRequired" "Service token does not authorize this DID"))
      (when-not (handles/hosted? settings handle) (handles/verify-external! settings did handle))
      (let [hash (accounts/password! (get body "password"))
            signing (crypto/keypair "ES256") rotation (when (str/starts-with? did "did:plc:") (crypto/keypair "ES256K"))
            origin (identity/origin! (get settings :plc-url "https://plc.directory"))
            audit (when rotation
                    (try (directory/audit! (:http-client settings) origin did)
                         (catch clojure.lang.ExceptionInfo e
                           (if (= :plc-directory (:type (ex-data e)))
                             (if (:retryable (ex-data e))
                               (errors/raise! 503 "DirectoryUnavailable" "PLC directory is temporarily unavailable")
                               (errors/raise! 409 "IdentityMismatch" "PLC audit could not be verified"))
                             (throw e)))))
            document (if rotation (plc/did-document (:data audit)) (:document proof))
            source-key (identity/signing-key document) verified-key (identity/signing-key (:document proof))]
        ;; Reject a signing-key change between service-token verification and
        ;; retaining the source identity snapshot for later repository import.
        (when-not (and source-key (= (:algorithm source-key) (:algorithm verified-key))
                       (= (vec (:public source-key)) (vec (:public verified-key))))
          (errors/raise! 409 "IdentityMismatch" "Source identity changed; obtain a fresh service token"))
        (try
          (db/transact! ds
            (fn [conn]
              (service-auth/consume! conn proof)
              (db/execute! conn "INSERT INTO accounts(did, handle, email, password_hash, status, imported) VALUES (?, ?, ?, ?, 'deactivated', true)"
                           did handle address hash)
              (registry/reserve! conn did handle)
              (when-not rotation (registry/reserve! conn did (str/lower-case (subs did 8))))
              (invites/consume! conn settings (get body "inviteCode") did)
              (let [signing (or (reserved-keys/consume! conn settings did) signing)]
                (db/execute! conn "INSERT INTO repositories(did, signing_key, public_key) VALUES (?, ?, ?)"
                             did (crypto/seal (:master-key settings) did (:private signing)) (:public signing)))
              (repo/commit! conn settings (assoc (repo/state conn did) :suppress-events? true))
              (db/execute! conn "INSERT INTO account_imports(did, source_document) VALUES (?, ?::jsonb)" did (json/write-str document))
              (when rotation
                (let [op (get (some #(when (= (:head audit) (get % "cid")) %) (:entries audit)) "operation")]
                  (db/execute! conn "INSERT INTO plc_identities(did, directory_url, operation, operation_cid, rotation_key, rotation_public, status)
                                     VALUES (?, ?, ?, ?, ?, ?, 'prepared')"
                               did origin (codec/encode op) (:head audit)
                               (crypto/seal (:master-key settings) (str did ":plc-rotation") (:private rotation)) (:public rotation))))
              (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ?" did))]
                (when (:email-enabled settings) (accounts/issue-email! conn account "confirm-email"))
                (merge (accounts/public-account account) (auth/issue! conn settings did nil) {:didDoc document}))))
          (catch java.sql.SQLException e
            (if (= "23505" (.getSQLState e))
              (errors/raise! 400 "HandleNotAvailable" "DID, handle or email is already registered")
              (throw e))))))))

(defn- activation-snapshot! [conn settings request]
  (let [account (auth/authenticate! conn settings request {:allow-deactivated? true}) did (:did account)]
    (auth/require-primary! account)
    {:account account :repo (repo/state conn did)
     :import (first (db/query conn "SELECT repository_imported FROM account_imports WHERE did = ?" did))
     :identity (first (db/query conn "SELECT directory_url, operation_cid, rotation_public FROM plc_identities WHERE did = ?" did))
     :pending? (boolean (seq (db/query conn "SELECT 1 FROM handle_updates WHERE did = ?" did)))}))

(defn- activation-version [snapshot]
  [(select-keys (:account snapshot) [:did :handle :status])
   (:head (:repo snapshot)) (vec (:public_key (:repo snapshot)))
   (:import snapshot) (:pending? snapshot)
   (update (:identity snapshot) :rotation_public #(when % (vec %)))])

(defn activate!
  "Activate a prepared destination only after a complete repository import and
  fresh remote identity verification. Network I/O is outside database locks;
  authorization and the snapshot are checked again before atomic publication."
  [ds settings resolver request]
  (let [snapshot (db/transact! ds
                   (fn [conn]
                     (let [snapshot (activation-snapshot! conn settings request)]
                       (if (:import snapshot) snapshot
                           (do (accounts/activate! conn (:account snapshot)) nil)))))]
    (when snapshot
      (when-not (get-in snapshot [:import :repository_imported])
        (errors/raise! 400 "MigrationIncomplete" "Import a complete repository before activating this account"))
      (when (:pending? snapshot) (errors/raise! 409 "IdentityUpdatePending" "Finish the pending identity update before activation"))
      (let [account (:account snapshot) did (:did account)
            audit (when (str/starts-with? did "did:plc:")
                    (try (directory/audit! (:http-client settings) (get-in snapshot [:identity :directory_url]) did)
                         (catch Exception _ (errors/raise! 503 "DirectoryUnavailable" "PLC audit could not be verified"))))
            document (if (str/starts-with? did "did:plc:") (plc/did-document (:data audit))
                         (identity/resolve-did! resolver did))
            key (identity/signing-key document)]
        (when-not (and (= did (get document "id"))
                       (= "ES256" (:algorithm key))
                       (= (vec (get-in snapshot [:repo :public_key])) (vec (:public key)))
                       (= (:public-url settings) (identity/pds-endpoint document))
                       (= (:handle account) (identity/claimed-handle document))
                       (or (nil? audit)
                           (some #{(plc/did-key {:algorithm "ES256K" :public (get-in snapshot [:identity :rotation_public])})}
                                 (get-in audit [:data "rotationKeys"]))))
          (errors/raise! 409 "IdentityMismatch" "DID credentials must match the destination key, handle and PDS endpoint"))
        (when-not (handles/hosted? settings (:handle account))
          (handles/verify-external! settings did (:handle account)))
        (db/transact! ds
          (fn [conn]
            (let [current (activation-snapshot! conn settings request)]
              ;; A concurrent successful activation is an idempotent retry.
              (when (and (nil? (:import current)) (not= "active" (get-in current [:account :status])))
                (errors/raise! 409 "InvalidSwap" "Account changed during identity verification; retry"))
              (when (:import current)
                (when-not (= (activation-version snapshot) (activation-version current))
                  (errors/raise! 409 "InvalidSwap" "Account or repository changed during identity verification; retry"))
                (when audit
                  (let [operation (get (some #(when (= (:head audit) (get % "cid")) %) (:entries audit)) "operation")]
                    (db/execute! conn "UPDATE plc_identities SET status = 'ready', operation = ?, operation_cid = ?, confirmed_at = now() WHERE did = ?"
                                 (codec/encode operation) (:head audit) did)))
                (db/execute! conn "DELETE FROM account_imports WHERE did = ?" did)
                (events/append! conn did "identity" {"did" did "handle" (:handle account)})
                (accounts/activate! conn (:account current))))))))
    nil))
