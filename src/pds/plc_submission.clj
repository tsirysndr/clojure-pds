(ns pds.plc-submission
  (:require [pds.auth :as auth]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.handles :as handles]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.protocol.codec :as codec]))

(defn- operation! [body]
  (let [operation (get body "operation")]
    (try (plc/operation! operation)
         (catch Exception _ (errors/invalid! "Invalid PLC operation")))
    (when-not (= "plc_operation" (get operation "type"))
      (errors/invalid! "Expected a PLC update operation"))
    operation))

(defn- snapshot! [conn settings request operation cid]
  (let [account (auth/authenticate! conn settings request {:allow-deactivated? true})
        did (:did account)
        identity (first (db/query conn "SELECT * FROM plc_identities WHERE did = ? AND status IN ('ready', 'prepared')" did))
        repo (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" did))
        pending (first (db/query conn "SELECT operation_kind, operation_cid FROM handle_updates WHERE did = ?" did))]
    (when-not identity (errors/raise! 400 "UnsupportedDID" "This account has no managed PLC identity"))
    (when-not (and (some #{(plc/did-key {:algorithm "ES256K" :public (:rotation_public identity)})} (get operation "rotationKeys"))
                   (= (plc/did-key {:algorithm "ES256" :public (:public_key repo)}) (get-in operation ["verificationMethods" "atproto"]))
                   (= "AtprotoPersonalDataServer" (get-in operation ["services" "atproto_pds" "type"]))
                   (= (:public-url settings) (get-in operation ["services" "atproto_pds" "endpoint"]))
                   (= (str "at://" (:handle account)) (first (get operation "alsoKnownAs"))))
      (errors/invalid! "PLC credentials must match this PDS and the account's current handle"))
    (when (and pending (not (and (= "submit" (:operation_kind pending)) (= cid (:operation_cid pending)))))
      (errors/raise! 409 "IdentityUpdatePending" "Finish the pending identity update first"))
    {:account account :identity identity :pending pending}))

(defn- authorized! [settings did identity operation cid]
  (let [audit (try (directory/audit! (:http-client settings) (:directory_url identity) did)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :plc-directory (:type (ex-data e)))
                       (if (:retryable (ex-data e))
                         (errors/raise! 503 "DirectoryUnavailable" "PLC directory is temporarily unavailable")
                         (errors/raise! 409 "IdentityMismatch" "PLC audit could not be verified"))
                       (throw e))))]
    (when-not (= cid (:head audit))
      (when-not (and (:data audit) (= (:head audit) (get operation "prev")))
        (errors/raise! 409 "IdentityMismatch" "PLC operation does not extend the current directory head"))
      (try (plc/signer! (get-in audit [:data "rotationKeys"]) operation)
           (catch Exception _ (errors/invalid! "PLC signature is not authorized by the current directory keys"))))))

(defn submit! [ds settings request body]
  (let [operation (operation! body) cid (plc/operation-cid operation)
        {:keys [account identity pending]} (db/transact! ds #(snapshot! % settings request operation cid))
        did (:did account)]
    ;; An existing job has already passed validation; its worker reconciles any
    ;; remote acceptance before retrying the exact stored bytes.
    (when-not pending (authorized! settings did identity operation cid))
    (db/transact! ds
      (fn [conn]
        (let [{fresh :account current :identity job :pending} (snapshot! conn settings request operation cid)]
          (when-not (= did (:did fresh)) (errors/raise! 409 "IdentityMismatch" "Account changed"))
          (cond
            job (db/execute! conn "UPDATE handle_updates SET status = 'pending', available_at = now(), last_error = NULL
                                    WHERE did = ? AND status = 'failed'" did)
            (= cid (:operation_cid current)) nil
            :else
            (do
              (when-not (and (nil? pending) (= (:operation_cid identity) (:operation_cid current)))
                (errors/raise! 409 "IdentityMismatch" "Identity changed; retry with fresh state"))
              (db/execute! conn "INSERT INTO handle_updates(did, target_handle, external_handle, operation, operation_cid, directory_url, operation_kind)
                                 VALUES (?, ?, false, ?, ?, ?, 'submit')"
                           did (:handle fresh) (codec/encode operation) cid (:directory_url current)))))))
    (handles/process-one! ds settings did)
    (with-open [conn (db/connection ds)]
      (when-not (= cid (:operation_cid (first (db/query conn "SELECT operation_cid FROM plc_identities WHERE did = ? AND status = 'ready'" did))))
        (errors/raise! 503 "IdentityUpdatePending" "PLC operation is pending; retry the same operation after directory confirmation"))))
  nil)
