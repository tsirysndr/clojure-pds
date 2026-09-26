(ns pds.migration
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.email :as email]
            [pds.errors :as errors]
            [pds.handle-registry :as registry]
            [pds.handles :as handles]
            [pds.identity :as identity]
            [pds.invites :as invites]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.request :as request]
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
              (db/execute! conn "INSERT INTO repositories(did, signing_key, public_key) VALUES (?, ?, ?)"
                           did (crypto/seal (:master-key settings) did (:private signing)) (:public signing))
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
