(ns pds.handles
  (:require [clojure.string :as str]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.events :as events]
            [pds.handle-registry :as registry]
            [pds.identity :as identity]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-keys :as keys]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax])
  (:import [java.util UUID]))

(defn hosted? [settings handle]
  (let [suffix (str "." (:user-domain settings))]
    (and (str/ends-with? handle suffix)
         (not (str/includes? (subs handle 0 (- (count handle) (count suffix))) ".")))))

(defn normalize! [settings handle]
  (when-not (syntax/handle? handle) (errors/raise! 400 "InvalidHandle" "Invalid handle"))
  (let [handle (str/lower-case handle)]
    (when (= handle (:hostname settings)) (errors/raise! 400 "HandleNotAvailable" "The service hostname is reserved"))
    (when-not (or (hosted? settings handle) (identity/resolvable-handle? handle))
      (errors/raise! 400 "InvalidHandle" "Handle cannot be resolved"))
    handle))

(defn verify-external! [settings did handle]
  ;; Do not consult the local registry as proof of control of an external name.
  (let [resolver (identity/resolver (select-keys settings [:http-client :txt-lookup :fetch]))]
    (when-not (= did (identity/resolve-handle! resolver handle))
      (errors/raise! 400 "InvalidHandle" "Custom handle does not resolve to this DID"))))

(defn- apply-handle! [conn account handle]
  (when-not (= handle (:handle account))
    (registry/reserve! conn (:did account) handle)
    (db/execute! conn "UPDATE accounts SET handle = ? WHERE did = ?" handle (:did account))
    (registry/release! conn (:did account) (:handle account))
    (events/append! conn (:did account) "identity" {"did" (:did account) "handle" handle})))

(defn- snapshot! [conn settings request]
  (let [account (auth/authenticate! conn settings request)]
    {:account account
     :plc (first (db/query conn "SELECT * FROM plc_identities WHERE did = ? AND status = 'ready'" (:did account)))
     :public-key (:public_key (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" (:did account))))
     :pending (first (db/query conn "SELECT target_handle, operation_kind FROM handle_updates WHERE did = ?" (:did account)))}))

(defn- prepare-operation [settings {:keys [account plc public-key]} handle]
  (when-not plc (errors/raise! 400 "UnsupportedDID" "This PLC identity is not managed by this server"))
  (let [audit (directory/audit! (:http-client settings) (:directory_url plc) (:did account))
        data (:data audit)
        server-key (plc/did-key {:algorithm "ES256K" :public (:rotation_public plc)})]
    (when-not (and data (some #{server-key} (get data "rotationKeys"))
                   (= (plc/did-key {:algorithm "ES256" :public public-key}) (get-in data ["verificationMethods" "atproto"]))
                   (= (str/replace (:public-url settings) #"/$" "")
                      (some-> (get-in data ["services" "atproto_pds" "endpoint"]) (str/replace #"/$" ""))))
      (errors/raise! 409 "IdentityMismatch" "Directory credentials no longer match this PDS"))
    (let [aliases (into [(str "at://" handle)]
                       (remove #(and (str/starts-with? % "at://") (syntax/handle? (subs % 5)))) (get data "alsoKnownAs"))
          unsigned (assoc (dissoc data "did") "type" "plc_operation" "prev" (:head audit) "alsoKnownAs" aliases)
          key {:algorithm "ES256K" :private (crypto/unseal (:master-key settings) (str (:did account) ":plc-rotation") (:rotation_key plc))}]
      (plc/sign-operation unsigned key))))

(defn- claim! [ds did]
  (db/transact! ds
    (fn [conn]
      (when-let [job (first (db/query conn
        "SELECT * FROM handle_updates WHERE (?::text IS NULL OR did = ?)
         AND ((status = 'pending' AND available_at <= now()) OR (status = 'working' AND lease_until <= now()))
         ORDER BY available_at, did FOR UPDATE SKIP LOCKED LIMIT 1" did did))]
        (let [lease (UUID/randomUUID)]
          (db/execute! conn "UPDATE handle_updates SET status = 'working', attempts = attempts + 1,
                             lease_token = ?, lease_until = now() + interval '60 seconds' WHERE did = ?" lease (:did job))
          (assoc job :lease lease :attempts (inc (:attempts job))))))))

(defn process-one! [ds settings did]
  (when-let [job (claim! ds did)]
    (let [outcome (try
                    (let [op (codec/decode (:operation job))]
                      (when-not (= (:operation_cid job) (plc/operation-cid op)) (throw (ex-info "Stored operation mismatch" {:retryable false})))
                      (keys/validate-job! settings job op)
                      (directory/ensure-operation! (:http-client settings) (:directory_url job) (:did job) op
                                                   #(when (:external_handle job) (verify-external! settings (:did job) (:target_handle job))))
                      {:success true})
                    (catch Exception e {:retryable (not= false (:retryable (ex-data e)))
                                        :reason (if (= :plc-directory (:type (ex-data e))) (name (:reason (ex-data e))) "update-failed")}))]
      (if (:success outcome)
        (db/transact! ds
          (fn [conn]
            (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" (:did job)))
                  claimed (first (db/query conn "SELECT did FROM handle_updates WHERE did = ? AND status = 'working' AND lease_token = ? FOR UPDATE"
                                           (:did job) (:lease job)))]
              (when claimed
                ;; Deletion is blocked while a job exists. Deactivation/takedown
                ;; do not undo a previously authorized directory change.
                (db/execute! conn "UPDATE plc_identities SET operation = ?, operation_cid = ?, confirmed_at = now(), status = 'ready' WHERE did = ?"
                             (:operation job) (:operation_cid job) (:did job))
                (keys/install! conn job)
                (db/execute! conn "DELETE FROM handle_updates WHERE did = ?" (:did job))
                (if (#{"submit" "rotate"} (:operation_kind job))
                  (events/append! conn (:did job) "identity" {"did" (:did job) "handle" (:handle account)})
                  (apply-handle! conn account (:target_handle job)))
                :updated))))
        (do (db/transact! ds
              #(db/execute! % "UPDATE handle_updates SET status = ?, lease_token = NULL, lease_until = NULL, last_error = ?,
                                available_at = now() + (? * interval '1 second') WHERE did = ? AND lease_token = ?"
                            (if (:retryable outcome) "pending" "failed") (:reason outcome)
                            (long (min 3600 (* 5 (Math/pow 2 (min 10 (dec (:attempts job))))))) (:did job) (:lease job)))
            :pending)))))

(defn update! [ds settings request body]
  (let [handle (normalize! settings (get body "handle"))
        snapshot (db/transact! ds #(snapshot! % settings request))
        {:keys [account pending]} snapshot did (:did account)]
    (when (and pending (or (not= "handle" (:operation_kind pending)) (not= handle (:target_handle pending))))
      (errors/raise! 409 "IdentityUpdatePending" "Finish the pending handle update first"))
    (when-not (and (= handle (:handle account)) (nil? pending))
      (when (and (nil? pending) (not (hosted? settings handle))) (verify-external! settings did handle))
      (let [plc? (str/starts-with? did "did:plc:")
            operation (when (and plc? (nil? pending)) (prepare-operation settings snapshot handle))]
        (db/transact! ds
          (fn [conn]
            (let [{fresh :account job :pending current :plc} (snapshot! conn settings request)]
              (when-not (= did (:did fresh)) (errors/raise! 409 "IdentityMismatch" "Account changed"))
              (if plc?
                (if job
                  (do (when-not (and (= "handle" (:operation_kind job)) (= handle (:target_handle job)))
                        (errors/raise! 409 "IdentityUpdatePending" "Finish the pending identity update first"))
                      (db/execute! conn "UPDATE handle_updates SET status = 'pending', available_at = now(), last_error = NULL WHERE did = ? AND status = 'failed'" did))
                  (when-not (= handle (:handle fresh))
                    (when-not (and operation (= (:operation_cid (:plc snapshot)) (:operation_cid current)))
                      (errors/raise! 409 "IdentityMismatch" "Identity changed; retry with fresh state"))
                    (registry/reserve! conn did handle)
                    (db/execute! conn "INSERT INTO handle_updates(did, target_handle, external_handle, operation, operation_cid, directory_url)
                                       VALUES (?, ?, ?, ?, ?, ?)" did handle (not (hosted? settings handle))
                                 (codec/encode operation) (plc/operation-cid operation) (:directory_url current))))
                (apply-handle! conn fresh handle)))))
        (when plc?
          (process-one! ds settings did)
          (with-open [conn (db/connection ds)]
            (when-not (= handle (:handle (first (db/query conn "SELECT handle FROM accounts WHERE did = ?" did))))
              (errors/raise! 503 "IdentityUpdatePending" "Handle update is pending; retry the same handle after directory confirmation"))))))
    nil))

(defn recommended [conn settings account]
  (let [repo (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" (:did account)))
        identity (first (db/query conn "SELECT operation, status, rotation_public FROM plc_identities WHERE did = ? AND status IN ('ready', 'prepared')" (:did account)))]
    (cond-> {:alsoKnownAs [(str "at://" (:handle account))]
             :verificationMethods {:atproto (plc/did-key {:algorithm "ES256" :public (:public_key repo)})}
             :services {:atproto_pds {:type "AtprotoPersonalDataServer" :endpoint (:public-url settings)}}}
      identity (assoc :rotationKeys (if (= "prepared" (:status identity))
                                     [(plc/did-key {:algorithm "ES256K" :public (:rotation_public identity)})]
                                     (get (plc/normalize (codec/decode (:operation identity))) "rotationKeys"))))))
