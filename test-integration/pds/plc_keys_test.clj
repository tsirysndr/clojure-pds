(ns pds.plc-keys-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.admin-accounts :as admin]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.events :as events]
            [pds.handles :as handles]
            [pds.handles-test :as handle-test]
            [pds.identity-admin :as cli]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as tls]
            [pds.plc-keys :as keys]
            [pds.plc-provision-test :as provision]
            [pds.plc-signing :as signing]
            [pds.plc-signing-test :as signing-test]
            [pds.plc-submission :as submission]
            [pds.plc-submission-test :as submission-test]
            [pds.plc-test :as operations]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(use-fixtures :each fixture/isolated-database)
(defn tx [f] (db/transact! fixture/*ds* f))
(defn stored [did] (first (provision/rows "SELECT * FROM plc_identities WHERE did = ?" did)))
(defn job [did] (first (provision/rows "SELECT * FROM handle_updates WHERE did = ?" did)))
(defn old-key [settings did row]
  {:algorithm "ES256K" :public (:rotation_public row)
   :private (crypto/unseal (:master-key settings) (str did ":plc-rotation") (:rotation_key row))})
(defn rotate [settings did expected]
  (cli/execute! fixture/*ds* settings (cli/command! ["rotate-plc-key" did expected])))
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (or (:error (ex-data e)) (:reason (ex-data e))))))

(defn upstream! [data]
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [path (Files/createTempFile "pds-rotation-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (str path) (json/write-str data))
        (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/verify-plc-rotation.mjs" (str path)]) (.redirectErrorStream true)))]
          (try
            (is (.waitFor process 45 TimeUnit/SECONDS))
            (when-not (.isAlive process) (is (zero? (.exitValue process)) (slurp (.getInputStream process))))
            (finally (when (.isAlive process) (.destroyForcibly process)))))
        (finally (Files/deleteIfExists path))))))

(defn child-status [did]
  (let [builder (ProcessBuilder. ["mise" "exec" "--" "clojure" "-M:identity" "status" did]) env (.environment builder)]
    (.putAll env {"PDS_DATABASE_URL" (.getURL fixture/*ds*) "PDS_DATABASE_USER" (.getUser fixture/*ds*)
                  "PDS_DATABASE_PASSWORD" (.getPassword fixture/*ds*)})
    (.remove env "PDS_MASTER_KEY")
    (let [process (.start builder) output (future (slurp (.getInputStream process))) errors (future (slurp (.getErrorStream process)))]
      (try
        (is (.waitFor process 45 TimeUnit/SECONDS))
        (when-not (.isAlive process)
          (is (zero? (.exitValue process)) "Status CLI succeeds without needing the master key")
          (json/read-str (deref output 5000 "{}")))
        (finally
          (when (.isAlive process) (.destroyForcibly process))
          (deref output 5000 nil) (deref errors 5000 nil))))))

(deftest control-key-rotation-preserves-priority-data-and-repository
  (tls/with-directory
    (fn [{:keys [client origin calls logs]}]
      (let [settings (provision/settings client origin) recovery (plc/did-key (crypto/keypair "ES256"))
            alice (accounts/create! fixture/*ds* settings (assoc (provision/signup) "recoveryKey" recovery)) did (:did alice)
            augmented (submission-test/sign-update settings alice {"alsoKnownAs" ["at://alice.example.com" "https://profile.example.com"]
                         "services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" (:public-url settings)}
                                     "example" {"type" "ExampleService" "endpoint" "https://example.net"}}})
            _ (submission/submit! fixture/*ds* settings (handle-test/request alice) {"operation" augmented})
            original (stored did) expected (:operation_cid original) signer (old-key settings did original)
            before-repo (tx #(select-keys (repo/state % did) [:head :rev :signing_key :public_key]))
            result (rotate settings did expected) new (stored did)
            audit (directory/audit! client origin did) data (:data audit)]
        (is (= "completed" (:state result)))
        (is (= (:operationCid result) (:operation_cid new) (:head audit)))
        (is (not= (vec (:rotation_public original)) (vec (:rotation_public new))))
        (is (= [recovery (:rotationKey result)] (get data "rotationKeys")))
        (is (= (dissoc (plc/operation-data did augmented) "rotationKeys") (dissoc data "rotationKeys")))
        (let [after-repo (tx #(select-keys (repo/state % did) [:head :rev :signing_key :public_key]))]
          (is (= (update-vals before-repo #(if (bytes? %) (vec %) %))
                 (update-vals after-repo #(if (bytes? %) (vec %) %)))))
        (is (nil? (job did)))
        (let [private (:private (old-key settings did new))]
          (is (not= (vec private) (vec (:rotation_key new))))
          (is (crypto/verify "ES256K" (:rotation_public new) (codec/utf8 "new key") (crypto/sign "ES256K" private (codec/utf8 "new key")))))
        (let [count-before (count (tls/posts calls)) events-before (count (handle-test/changes did))]
          (is (= result (rotate settings did expected)))
          (is (= count-before (count (tls/posts calls))))
          (is (= events-before (count (handle-test/changes did)))))
        (is (thrown? Exception (plc/signer! (get data "rotationKeys") (operations/update-op (codec/decode (:operation new)) signer {}))))
        ;; A normal owner operation proves the replacement private key is now used.
        (handles/update! fixture/*ds* settings (handle-test/request alice) {"handle" "renamed.example.com"})
        (is (= "renamed.example.com" (handle-test/current-handle did)))
        (is (= result (rotate settings did expected)) "Receipt remains idempotent after a later handle operation")
        (let [status (child-status did)]
          (is (= "ready" (get status "state")))
          (is (= (:rotationKey result) (get status "rotationKey")))
          (is (= #{"did" "operationCid" "rotationKey" "state"} (set (keys status)))))
        (upstream! {:did did :operations (mapv #(get % "operation") (get @logs did))
                    :data (:data (directory/audit! client origin did)) :position 1
                    :oldKey (plc/did-key signer) :newKey (:rotationKey result)})))))

(deftest pending-rotation-retries-exact-material-and-blocks-competing-work
  (tls/with-directory
    (fn [{:keys [client origin mode calls]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            original (stored did) expected (:operation_cid original)]
        (reset! mode :ignore)
        (is (= "pending" (:state (rotate settings did expected))))
        (let [pending (job did) op (:operation_cid pending) key (vec (:next_rotation_key pending))]
          (is (= "rotate" (:operation_kind pending)))
          (is (= expected (:operation_cid (stored did))))
          (is (= (vec (:rotation_key original)) (vec (:rotation_key (stored did)))))
          (is (= "IdentityUpdatePending" (error #(handles/update! fixture/*ds* settings (handle-test/request alice) {"handle" "next.example.com"}))))
          (is (= "IdentityUpdatePending" (error #(signing/request-signature! fixture/*ds* settings (handle-test/request alice)))))
          (is (= "IdentityUpdatePending" (error #(tx (fn [conn] (admin/delete! conn {"did" did}))))))
          (is (= "IdentityUpdatePending" (error #(rotate settings did (codec/cid (byte-array [9]))))))
          (reset! mode :reject)
          (is (= "failed" (:state (rotate settings did expected))))
          (is (= op (:operation_cid (job did))))
          (is (= key (vec (:next_rotation_key (job did)))))
          (reset! mode :accept-error)
          (is (= "completed" (:state (rotate settings did expected))))
          (is (= op (:operation_cid (stored did))))
          (is (= key (vec (:rotation_key (stored did)))))
          (is (= 4 (count (tls/posts calls))))
          (is (= 2 (count (handle-test/changes did)))))))))

(deftest accepted-operation-survives-local-rollback-and-stale-lease
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            original (stored did) expected (:operation_cid original)]
        (keys/enqueue! fixture/*ds* settings did expected)
        (let [saved (job did)]
          (with-redefs [events/append! (fn [& _] (throw (ex-info "Simulated local commit failure" {})))]
            (is (thrown? Exception (handles/process-one! fixture/*ds* settings did))))
          (is (= 2 (count (tls/posts calls))))
          (is (= expected (:operation_cid (stored did))))
          (is (empty? (provision/rows "SELECT * FROM plc_key_rotations")))
          (handle-test/due!)
          (let [ensure! directory/ensure-operation!]
            (with-redefs [directory/ensure-operation!
                          (fn [& args]
                            (let [result (apply ensure! args)]
                              (handle-test/due!)
                              (with-redefs [directory/ensure-operation! ensure!]
                                (is (= :updated (handles/process-one! fixture/*ds* settings did))))
                              result))]
              (is (nil? (handles/process-one! fixture/*ds* settings did)) "Stale owner cannot install or emit twice")))
          (is (= (:operation_cid saved) (:operation_cid (stored did))))
          (is (= (vec (:next_rotation_key saved)) (vec (:rotation_key (stored did)))))
          (is (= 1 (count (provision/rows "SELECT * FROM plc_key_rotations"))))
          (is (= 2 (count (tls/posts calls))))
          (is (= 2 (count (handle-test/changes did)))))))))

(deftest remote-conflicts-and-corrupt-material-never-replace-local-keys
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            original (stored did) expected (:operation_cid original)]
        (is (= "IdentityMismatch" (error #(rotate settings did (codec/cid (byte-array [7]))))))
        (keys/enqueue! fixture/*ds* settings did expected)
        (tx #(db/execute! % "UPDATE handle_updates SET next_rotation_key = ? WHERE did = ?" (byte-array [1 2 3]) did))
        (is (= :pending (handles/process-one! fixture/*ds* settings did)))
        (is (= "failed" (:status (job did))))
        (is (= 1 (count (tls/posts calls))) "Invalid sealed material is detected before any POST")
        (is (= (vec (:rotation_key original)) (vec (:rotation_key (stored did)))))
        (is (empty? (provision/rows "SELECT * FROM plc_key_rotations"))))))
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin)
            alice (accounts/create! fixture/*ds* settings (assoc (provision/signup) "handle" "bob.example.com" "email" "bob@example.com")) did (:did alice)
            original (stored did) expected (:operation_cid original)
            competing (submission-test/sign-update settings alice {"alsoKnownAs" ["at://outside.example.com"]})]
        (keys/enqueue! fixture/*ds* settings did expected)
        (directory/ensure-operation! client origin did competing)
        (is (= :pending (handles/process-one! fixture/*ds* settings did)))
        (is (= "failed" (:status (job did))))
        (is (= "conflict" (:last_error (job did))))
        (is (= expected (:operation_cid (stored did))))
        (is (= (vec (:rotation_public original)) (vec (:rotation_public (stored did)))))
        (is (= 2 (count (tls/posts calls))))))))

(deftest concurrent-operators-converge-on-one-rotation
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            expected (:operation_cid (stored did)) start (promise)
            workers (mapv (fn [_] (future @start (keys/enqueue! fixture/*ds* settings did expected))) (range 4))]
        (deliver start true)
        (let [results (mapv #(deref % 20000 :timeout) workers)]
          (is (not-any? #{:timeout} results))
          (is (= 1 (count (set (map :operationCid results))))))
        (is (= :updated (handles/process-one! fixture/*ds* settings did)))
        (is (= 1 (count (provision/rows "SELECT * FROM plc_key_rotations"))))
        (is (= 2 (count (tls/posts calls))))))))

(deftest preparation-rechecks-local-state-and-remote-authority
  (tls/with-directory
    (fn [{:keys [client origin calls audit-body]}]
      (let [settings (provision/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)
            expected (:operation_cid (stored did)) audit! directory/audit!]
        (reset! audit-body "{}")
        (is (= :invalid-audit (error #(keys/enqueue! fixture/*ds* settings did expected))))
        (is (nil? (job did)))
        (reset! audit-body nil)
        ;; Another account mutation may commit while the audit is fetched. It
        ;; must be observed before any new key or queue row is installed.
        (with-redefs [directory/audit! (fn [& args]
                                        (let [result (apply audit! args)]
                                          (tx #(admin/delete! % {"did" did}))
                                          result))]
          (is (= "AccountNotFound" (error #(keys/enqueue! fixture/*ds* settings did expected)))))
        (is (nil? (job did)))
        (is (= 1 (count (tls/posts calls)))))))
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin)
            alice (accounts/create! fixture/*ds* settings (assoc (provision/signup) "handle" "bob.example.com" "email" "bob@example.com"))
            did (:did alice) expected (:operation_cid (stored did))
            migrated (submission-test/sign-update settings alice {"services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" "https://another.example.com"}}})]
        (directory/ensure-operation! client origin did migrated)
        (is (= "IdentityMismatch" (error #(keys/enqueue! fixture/*ds* settings did expected))))
        (is (nil? (job did)))
        (is (= expected (:operation_cid (stored did))))
        (is (= 2 (count (tls/posts calls))))))))

(deftest rotation-preserves-inactive-and-moderated-status
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision/signup)) did (:did alice)]
        (doseq [status ["deactivated" "taken_down"]]
          (tx #(db/execute! % "UPDATE accounts SET status = ? WHERE did = ?" status did))
          (let [expected (:operation_cid (stored did))
                result (rotate (assoc settings :plc-url "https://wrong-directory.example.com") did expected)]
            (is (= "completed" (:state result)))
            (is (= status (:status (first (provision/rows "SELECT status FROM accounts WHERE did = ?" did)))))
            (is (= (:operationCid result) (:head (directory/audit! client origin did))))))
        (is (= 2 (count (provision/rows "SELECT * FROM plc_key_rotations"))))))))
