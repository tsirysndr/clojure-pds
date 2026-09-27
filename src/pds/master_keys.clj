(ns pds.master-keys
  "Offline master-key lifecycle. Serving processes hold shared database leases;
  rewrapping requires exclusive access and one all-or-nothing transaction."
  (:require [clojure.data.json :as json]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.protocol.codec :as codec])
  (:import [java.util Arrays]))

(def lock-id 731946283)
(defprotocol ServingLease
  (live? [lease] "Whether this process still holds its database maintenance lease."))
(defn- fail! [error message] (throw (ex-info message {:master-key-error error})))
(defn key! [env name]
  (let [key (try (crypto/unb64 (get env name "")) (catch Exception _ nil))]
    (when-not (and key (= 32 (alength ^bytes key)))
      (fail! "InvalidKey" (str name " must be a base64url-encoded 32-byte key")))
    key))
(defn fingerprint [key]
  (crypto/b64 (codec/sha256 (byte-array (concat (codec/utf8 "clojure-pds/master-key/v1/") key)))))

(def columns
  ;; Every use of crypto/seal is rewrapped here or deleted during rotation
  ;; (sessions, app passwords and unconsumed reserved signing keys).
  [{:table "repositories" :column "signing_key" :purpose identity}
   {:table "plc_identities" :column "rotation_key" :purpose #(str % ":plc-rotation")}
   {:table "handle_updates" :column "next_rotation_key" :purpose #(str % ":plc-rotation")}
   {:table "handle_updates" :column "next_signing_key" :purpose identity}
   {:table "account_totp" :column "sealed_secret" :purpose #(str "pds/totp/v1/" %)}])

(defn- sealed-columns! [conn old new]
  (into {}
    (for [{:keys [table column purpose]} columns]
      [(keyword (str table "." column))
       (loop [cursor nil total 0]
         (let [rows (db/query conn (str "SELECT did, " column " AS sealed FROM " table
                                        " WHERE " column " IS NOT NULL AND (?::text IS NULL OR did COLLATE \"C\" > ? COLLATE \"C\")"
                                        " ORDER BY did COLLATE \"C\" LIMIT 100 FOR UPDATE") cursor cursor)]
           (doseq [{:keys [did sealed]} rows]
             (let [plain (try (crypto/unseal old (purpose did) sealed)
                              (catch Exception _ (fail! "InvalidSealedMaterial" "An encrypted secret could not be verified; no changes committed")))]
               (try
                 (when new
                   (db/execute! conn (str "UPDATE " table " SET " column " = ? WHERE did = ?")
                                (crypto/seal new (purpose did) plain) did))
                 (finally (Arrays/fill ^bytes plain (byte 0))))))
           (if (seq rows) (recur (:did (last rows)) (+ total (count rows))) total)))])))

(defn- state [conn] (first (db/query conn "SELECT * FROM master_key_state WHERE id")))
(defn status! [ds]
  (with-open [conn (db/connection ds)]
    (if-let [row (state conn)]
      {:state "ready" :fingerprint (:fingerprint row) :generation (:generation row)}
      {:state "unregistered"})))

(defn- register! [conn key]
  ;; Serialize first adoption even when several serving instances start together.
  (db/query conn "SELECT pg_advisory_xact_lock(731946284)")
  (if-let [row (state conn)]
    (when-not (= (:fingerprint row) (fingerprint key))
      (fail! "MasterKeyMismatch" "Configured master key does not match this database"))
    (do (sealed-columns! conn key nil)
        (db/execute! conn "INSERT INTO master_key_state(id, fingerprint) VALUES (true, ?)" (fingerprint key)))))

(defn open-lease!
  "Use a dedicated unpooled datasource: this connection lives until shutdown.
  Older workers must be stopped/upgraded before introducing this lifecycle."
  [ds key]
  (let [conn (db/connection ds)]
    (try
      (db/query conn "SELECT set_config('application_name', 'clojure-pds/master-key-lease', false)")
      (when-not (:locked (first (db/query conn "SELECT pg_try_advisory_lock_shared(?) AS locked" lock-id)))
        (fail! "MasterKeyBusy" "Master-key maintenance is running"))
      (.setAutoCommit conn false)
      (try (register! conn key) (.commit conn)
           (catch Throwable t (.rollback conn) (throw t)))
      (.setAutoCommit conn true)
      (let [closed? (atom false)]
        (reify ServingLease
          (live? [_]
            (try
              (and (not @closed?) (.isValid conn 2)
                   (:held (first (db/query conn "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory'
                                                 AND pid = pg_backend_pid() AND classid = 0 AND objid = 731946283
                                                 AND objsubid = 1 AND mode = 'ShareLock' AND granted) AS held"))))
              (catch Exception _ false)))
          java.io.Closeable
          (close [_]
            (when (compare-and-set! closed? false true)
              (try (when-not (.isClosed conn) (db/query conn "SELECT pg_advisory_unlock_shared(?)" lock-id))
                   (finally (.close conn)))))))
      (catch Throwable t (.close conn) (throw t)))))

(defn- receipt [row]
  {:state "completed" :previousFingerprint (:previous_fingerprint row)
   :fingerprint (:fingerprint row) :generation (:generation row)
   :counts (json/read-str (:counts row) :key-fn keyword)})

(defn rewrap! [ds old new expected]
  (when-not (= expected (fingerprint old))
    (fail! "MasterKeyMismatch" "Expected fingerprint does not match PDS_MASTER_KEY"))
  (when (= expected (fingerprint new)) (fail! "InvalidKey" "The replacement key must be different"))
  (db/transact! ds
    (fn [conn]
      (when-not (:locked (first (db/query conn "SELECT pg_try_advisory_xact_lock(?) AS locked" lock-id)))
        (fail! "PdsRunning" "Stop every PDS instance and identity worker before rewrapping"))
      (if-let [done (first (db/query conn "SELECT *, counts::text AS counts FROM master_key_rotations WHERE previous_fingerprint = ?" expected))]
        (if (= (:fingerprint done) (fingerprint new)) (receipt done)
          (fail! "MasterKeyMismatch" "This master key was already replaced by a different key"))
        (do
          ;; Also serialize with migration and protect against accidental SQL
          ;; writers during this transaction. This does not permit online use.
          (db/query conn "SELECT pg_advisory_xact_lock(731946281)")
          (db/execute! conn "SET LOCAL lock_timeout = '5s'")
          (db/execute! conn "LOCK TABLE accounts, repositories, plc_identities, handle_updates, account_totp, sessions, app_passwords, reserved_signing_keys IN ACCESS EXCLUSIVE MODE")
          (register! conn old)
          (when (seq (db/query conn "SELECT 1 FROM master_key_rotations WHERE previous_fingerprint = ? OR fingerprint = ?" (fingerprint new) (fingerprint new)))
            (fail! "RetiredKey" "A previously used master key cannot be reused"))
          (let [counts (sealed-columns! conn old new)
                counts (assoc counts :sessions (db/execute! conn "DELETE FROM sessions")
                                     :app_passwords (db/execute! conn "DELETE FROM app_passwords")
                                     :reserved_signing_keys (db/execute! conn "DELETE FROM reserved_signing_keys"))
                generation (inc (:generation (state conn)))]
            (db/execute! conn "UPDATE master_key_state SET fingerprint = ?, generation = ?, updated_at = now() WHERE id" (fingerprint new) generation)
            (db/execute! conn "INSERT INTO master_key_rotations(previous_fingerprint, fingerprint, generation, counts) VALUES (?, ?, ?, ?::jsonb)"
                         expected (fingerprint new) generation (json/write-str counts))
            {:state "completed" :previousFingerprint expected :fingerprint (fingerprint new) :generation generation :counts counts}))))))
