(ns pds.plc-provision
  (:require [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.identity :as identity]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.protocol.codec :as codec])
  (:import [java.util UUID]
           [java.util.concurrent Executors TimeUnit]))

(defn prepare [settings handle recovery-key]
  (when recovery-key (plc/parse-key recovery-key))
  (let [signing (crypto/keypair "ES256") rotation (crypto/keypair "ES256K")
        op (plc/sign-operation
             {"type" "plc_operation" "prev" nil
              "rotationKeys" (cond-> [] recovery-key (conj recovery-key) true (conj (plc/did-key rotation)))
              "verificationMethods" {"atproto" (plc/did-key signing)}
              "alsoKnownAs" [(str "at://" handle)]
              "services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" (identity/origin! (:public-url settings))}}}
             rotation)
        did (plc/genesis-did op)]
    {:did did :operation (codec/encode op) :operation-cid (plc/operation-cid op)
     :signing-key (crypto/seal (:master-key settings) did (:private signing)) :signing-public (:public signing)
     :rotation-key (crypto/seal (:master-key settings) (str did ":plc-rotation") (:private rotation))
     :rotation-public (:public rotation) :recovery-key recovery-key
     :directory-url (identity/origin! (get settings :plc-url "https://plc.directory"))}))

(defn reserve! [conn prepared]
  (let [{:keys [did signing-key signing-public directory-url operation operation-cid rotation-key rotation-public recovery-key]} prepared]
    (db/execute! conn "INSERT INTO repositories(did, signing_key, public_key) VALUES (?, ?, ?)" did signing-key signing-public)
    (db/execute! conn "INSERT INTO plc_identities(did, directory_url, operation, operation_cid, rotation_key, rotation_public, recovery_key)
                       VALUES (?, ?, ?, ?, ?, ?, ?)" did directory-url operation operation-cid rotation-key rotation-public recovery-key)))

(defn- claim! [ds did]
  (db/transact! ds
    (fn [conn]
      (when-let [row (first (db/query conn
        "SELECT * FROM plc_identities WHERE (?::text IS NULL OR did = ?)
         AND ((status = 'pending' AND available_at <= now()) OR (status = 'working' AND lease_until <= now()))
         ORDER BY available_at, did FOR UPDATE SKIP LOCKED LIMIT 1" did did))]
        (let [lease (UUID/randomUUID)]
          (db/execute! conn "UPDATE plc_identities SET status = 'working', attempts = attempts + 1,
                             lease_token = ?, lease_until = now() + interval '60 seconds' WHERE did = ?" lease (:did row))
          (assoc row :lease lease :attempts (inc (:attempts row))))))))

(defn process-one!
  "Lease one durable operation, release the transaction during network I/O, then
  activate atomically through finalize!. Stale workers cannot finalize or requeue
  after a newer lease. A crash resumes the exact same signed operation."
  [ds settings did finalize!]
  (when-let [job (claim! ds did)]
    (let [outcome (try
                    (let [op (codec/decode (:operation job))]
                      (when-not (= (:operation_cid job) (plc/operation-cid op)) (throw (ex-info "Stored PLC operation mismatch" {:retryable false})))
                      (directory/ensure-operation! (:http-client settings) (:directory_url job) (:did job) op)
                      {:success true})
                    (catch Exception e {:success false :retryable (not= false (:retryable (ex-data e)))
                                        :reason (if (= :plc-directory (:type (ex-data e))) (name (:reason (ex-data e))) "provision-failed")}))]
      (if (:success outcome)
        (db/transact! ds
          (fn [conn]
            ;; Match account -> identity lock order used by signup retry.
            (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" (:did job)))
                  row (first (db/query conn "SELECT did FROM plc_identities WHERE did = ? AND status = 'working' AND lease_token = ? FOR UPDATE"
                                       (:did job) (:lease job)))]
              (when (and row (= "provisioning" (:status account)))
                (finalize! conn account)
                (db/execute! conn "UPDATE plc_identities SET status = 'ready', lease_token = NULL, lease_until = NULL,
                                   last_error = NULL, confirmed_at = now() WHERE did = ?" (:did job))
                :ready))))
        (do
          (db/transact! ds
            #(db/execute! % "UPDATE plc_identities SET status = ?, lease_token = NULL, lease_until = NULL, last_error = ?,
                              available_at = now() + (? * interval '1 second')
                            WHERE did = ? AND status = 'working' AND lease_token = ?"
                          (if (:retryable outcome) "pending" "failed") (:reason outcome)
                          (long (min 3600 (* 5 (Math/pow 2 (min 10 (dec (:attempts job))))))) (:did job) (:lease job)))
          :pending)))))

(defn start! [process!]
  (let [executor (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay executor
      ^Runnable (fn [] (try (process!) (catch Exception _ (binding [*out* *err*] (println "PLC provisioning will retry after lease expiry")))))
      1 1 TimeUnit/SECONDS)
    (fn [] (.shutdownNow executor) (.awaitTermination executor 20 TimeUnit/SECONDS))))
