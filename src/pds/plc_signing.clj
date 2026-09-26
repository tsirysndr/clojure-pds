(ns pds.plc-signing
  (:require [pds.accounts :as accounts]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.request :as request]))

(defn- snapshot! [conn settings request]
  (let [account (auth/authenticate! conn settings request {:allow-deactivated? true :allow-taken-down? true})]
    (auth/require-primary! account)
    (let [identity (first (db/query conn "SELECT * FROM plc_identities WHERE did = ? AND status = 'ready'" (:did account)))]
      (when-not identity (errors/raise! 400 "UnsupportedDID" "This account has no managed PLC rotation key"))
      (when (seq (db/query conn "SELECT 1 FROM handle_updates WHERE did = ?" (:did account)))
        (errors/raise! 409 "IdentityUpdatePending" "Finish the pending handle update before signing another operation"))
      {:account account :identity identity})))

(defn request-signature! [ds settings request]
  (db/transact! ds
    (fn [conn]
      (let [{:keys [account]} (snapshot! conn settings request)]
        (accounts/require-email! settings)
        (accounts/issue-email! conn account "plc-operation"))))
  nil)

(defn sign! [ds settings request body]
  (let [token (request/string! (get body "token") "token")
        snapshot (db/transact! ds
                   (fn [conn]
                     (let [{:keys [account] :as snapshot} (snapshot! conn settings request)]
                       ;; Check before network work, but only consume after successful signing.
                       (when (empty? (db/query conn "SELECT 1 FROM account_tokens WHERE token_hash = ? AND purpose = 'plc-operation'
                                                     AND did = ? AND email = ? AND expires_at > now()"
                                              (crypto/digest-token token) (:did account) (:email account)))
                         (errors/raise! 400 "InvalidToken" "Invalid or expired email token"))
                       snapshot)))
        {:keys [account identity]} snapshot
        audit (try (directory/audit! (:http-client settings) (:directory_url identity) (:did account))
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :plc-directory (:type (ex-data e)))
                       (if (:retryable (ex-data e))
                         (errors/raise! 503 "DirectoryUnavailable" "PLC directory is temporarily unavailable")
                         (errors/raise! 409 "IdentityMismatch" "PLC audit could not be verified"))
                       (throw e))))
        server-key (plc/did-key {:algorithm "ES256K" :public (:rotation_public identity)})]
    (when-not (and (:data audit) (some #{server-key} (get-in audit [:data "rotationKeys"])))
      (errors/raise! 409 "IdentityMismatch" "This PDS no longer has authority to sign PLC operations"))
    (db/transact! ds
      (fn [conn]
        (let [{fresh :account current :identity} (snapshot! conn settings request)]
          (when-not (and (= (:did fresh) (:did account))
                         (= (:email fresh) (:email account))
                         (= (:operation_cid current) (:operation_cid identity))
                         (= (vec (:rotation_public current)) (vec (:rotation_public identity))))
            (errors/raise! 409 "IdentityMismatch" "Account identity changed; retry with fresh state"))
          (let [unsigned (merge (dissoc (:data audit) "did")
                                (select-keys body ["rotationKeys" "alsoKnownAs" "verificationMethods" "services"])
                                {"type" "plc_operation" "prev" (:head audit)})
                key {:algorithm "ES256K" :private (crypto/unseal (:master-key settings) (str (:did fresh) ":plc-rotation") (:rotation_key current))}
                operation (try (plc/sign-operation unsigned key)
                               (catch Exception _ (errors/invalid! "Invalid PLC operation fields")))]
            ;; Account lock plus one-use token consumption serializes concurrent requests.
            (accounts/consume-token! conn "plc-operation" token (:did fresh) (:email fresh))
            {:operation operation}))))))
