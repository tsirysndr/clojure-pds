(ns pds.signing-keys-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.events :as events]
            [pds.firehose :as firehose]
            [pds.firehose-test :as stream]
            [pds.handles :as handles]
            [pds.handles-test :as handle-test]
            [pds.http :as http]
            [pds.identity-admin :as cli]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as tls]
            [pds.plc-keys :as control]
            [pds.plc-keys-test :as control-test]
            [pds.plc-provision-test :as provision]
            [pds.plc-submission-test :as submission-test]
            [pds.protocol.codec :as codec]
            [pds.protocol.repository :as repository]
            [pds.repo :as repo]
            [pds.repo-import :as repo-import]
            [pds.server-api-test :as api]
            [pds.service-auth-test :as service]
            [pds.signing-keys :as keys]
            [pds.sync-api-test :as sync]
            [pds.websocket-test :as ws])
  (:import [java.io ByteArrayInputStream]
           [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)
(defn tx [f] (db/transact! fixture/*ds* f))
(defn state [did] (first (provision/rows "SELECT * FROM repositories WHERE did = ?" did)))
(defn rotate [settings did expected] (cli/execute! fixture/*ds* settings (cli/command! ["rotate-signing-key" did expected])))
(defn seed! [settings]
  (let [account (accounts/create! fixture/*ds* settings (provision/signup)) did (:did account)]
    (tx #(repo/apply-writes! % settings did [{:action :create :collection "com.example.note" :rkey "one" :value {"$type" "com.example.note" "text" "retained"}}] nil))
    (tx #(db/execute! % "UPDATE records SET takedown_ref = 'case-1' WHERE did = ?" did))
    account))
(defn check-rotation! [settings alice]
  (let [did (:did alice) old (state did) expected (keys/public-key (:public_key old))
        before (tx #(repo/export-car % did)) record-rows (provision/rows "SELECT * FROM records WHERE did = ?" did)
        cursor (:seq (first (provision/rows "SELECT max(seq) AS seq FROM repo_events")))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (let [result (rotate settings did expected) current (state did)]
        (is (= "completed" (:state result)))
        (is (not= expected (:signingKey result)))
        (is (= (:repoCommit result) (:head current)))
        (is (pos? (compare (:rev current) (:rev old))))
        (is (= record-rows (provision/rows "SELECT * FROM records WHERE did = ?" did)))
        (with-open [client (HttpClient/newHttpClient)]
          (let [call #(api/xrpc client (:port server) %1 %2 %3 %4)
                exported (call "GET" (str "com.atproto.sync.getRepo?did=" did) nil nil)
                stream (ws/connect client (:port server) (str stream/path "?cursor=" cursor))]
            (try
              (is (= 200 (:status exported)))
              (is (= (:head current) (:head (repository/verify-car (:raw exported) did {:algorithm "ES256" :public (:public_key current)}))))
              (is (thrown? Exception (repository/verify-car (:raw exported) did {:algorithm "ES256" :public (:public_key old)})))
              (let [frames (vec (repeatedly 2 #(ws/receive stream)))]
                (is (= ["#identity" "#sync"] (mapv #(get (first (codec/decode-pair % 5000000)) "t") frames)))
                (sync/verify-upstream! "verify-signing-rotation.mjs" {:did did :before (crypto/b64 before) :after (crypto/b64 (:raw exported))
                                                                     :oldKey expected :newKey (:signingKey result) :frames (mapv crypto/b64 frames)}))
              (is (= 200 (:status (call "GET" "com.atproto.server.getSession" nil (:accessJwt alice)))))
              (let [response (call "GET" "com.atproto.server.getServiceAuth?aud=did:web:destination.example.com&lxm=com.atproto.server.createAccount" nil (:accessJwt alice))
                    {:keys [signature message]} (service/decode (get-in response [:body "token"]))]
                (is (= 200 (:status response)))
                (is (crypto/verify "ES256" (:public_key current) message signature))
                (is (false? (crypto/verify "ES256" (:public_key old) message signature))))
              (is (= result (rotate settings did expected)))
              (is (= 2 (count (provision/rows "SELECT * FROM repo_events WHERE seq > ?" cursor))))
              (is (= 200 (:status (call "POST" "com.atproto.repo.createRecord"
                                       {"repo" did "collection" "com.example.note" "rkey" "two" "record" {"$type" "com.example.note"}} (:accessJwt alice)))))
              (let [new-export (call "GET" (str "com.atproto.sync.getRepo?did=" did) nil nil)]
                (is (= 2 (count (:records (repository/verify-car (:raw new-export) did {:algorithm "ES256" :public (:public_key current)}))))))
              (finally (stream/stop! stream)))))
        result)
      (finally ((:stop! server))))))

(deftest hosted-web-rotation-is-atomic-and-replay-safe
  (let [settings (api/settings) alice (seed! settings) did (:did alice)
        result (check-rotation! settings alice)
        document (tx #(accounts/did-document % settings (accounts/resolve-account % did) (:public_key (state did))))]
    (is (= (subs (:signingKey result) 8) (get-in document [:verificationMethod 0 :publicKeyMultibase])))
    (is (empty? (provision/rows "SELECT * FROM handle_updates")))
    (is (not (contains? result :operationCid)))))

(deftest plc-signing-rotation-preserves-control-keys-and-confirms-before-install
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (seed! settings) did (:did alice)
            old (control-test/stored did) before (:data (directory/audit! client origin did))
            result (check-rotation! settings alice) after (:data (directory/audit! client origin did))]
        (is (= (assoc-in before ["verificationMethods" "atproto"] (:signingKey result)) after))
        (is (= (vec (:rotation_key old)) (vec (:rotation_key (control-test/stored did)))))
        (is (= (:operationCid result) (:head (directory/audit! client origin did))))
        (is (= "completed" (:state (control-test/rotate settings did (:operation_cid (control-test/stored did))))))
        (handles/update! fixture/*ds* settings (handle-test/request alice) {"handle" "renamed.example.com"})
        (is (= "renamed.example.com" (handle-test/current-handle did)))))))

(deftest pending-rotation-pauses-signing-and-signed-sync-until-confirmed
  (tls/with-directory
    (fn [{:keys [client origin mode]}]
      (let [settings (provision/settings client origin) alice (seed! settings) did (:did alice)
            old (state did) expected (keys/public-key (:public_key old)) before (tx #(repo/export-car % did))
            server (http/start! settings (app/handler settings fixture/*ds*))]
        (try
          (reset! mode :ignore)
          (is (= "pending" (:state (rotate settings did expected))))
          (with-open [client (HttpClient/newHttpClient)]
            (let [call #(api/xrpc client (:port server) %1 %2 %3 %4)
                  writes {"repo" did "collection" "com.example.note" "rkey" "blocked" "record" {"$type" "com.example.note"}}]
              (doseq [path [(str "com.atproto.sync.getRepo?did=" did) (str "com.atproto.sync.getLatestCommit?did=" did)
                            (str "com.atproto.sync.getRecord?did=" did "&collection=com.example.note&rkey=one")
                            (str "com.atproto.sync.getBlocks?did=" did "&cids=" (:head old))
                            "com.atproto.server.getServiceAuth?aud=did:web:destination.example.com&lxm=com.atproto.server.createAccount"]]
                (is (= "SigningKeyRotationPending" (get-in (call "GET" path nil (:accessJwt alice)) [:body "error"]))))
              (is (= 503 (:status (call "POST" "com.atproto.repo.createRecord" writes (:accessJwt alice)))))
              (is (= [] (get-in (call "GET" "com.atproto.sync.listRepos" nil nil) [:body "repos"])))
              (is (= 200 (:status (call "GET" "com.atproto.server.getSession" nil (:accessJwt alice)))))
              (is (= "SigningKeyRotationPending"
                     (control-test/error #(repo-import/import! fixture/*ds* settings
                                           {:headers {"authorization" (str "Bearer " (:accessJwt alice)) "content-type" "application/vnd.ipld.car"}
                                            :body (ByteArrayInputStream. before)}))))
              (doseq [event (provision/rows "SELECT seq FROM repo_events WHERE did = ? AND event_type IN ('commit', 'sync')" did)]
                (is (nil? (firehose/event fixture/*ds* (:seq event)))))
              (is (= (:head old) (:head (state did))))
              (is (= "IdentityUpdatePending" (control-test/error #(control/enqueue! fixture/*ds* settings did (:operation_cid (control-test/stored did))))))
              (reset! mode :accept-drop)
              (is (= "completed" (:state (rotate settings did expected))))
              (is (= 200 (:status (call "POST" "com.atproto.repo.createRecord" writes (:accessJwt alice)))))
              (is (= 1 (count (provision/rows "SELECT * FROM signing_key_rotations"))))))
          (finally ((:stop! server))))))))

(deftest accepted-key-survives-local-rollback-and-retries-with-the-same-material
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) alice (seed! settings) did (:did alice)
            old (state did) expected (keys/public-key (:public_key old))]
        (keys/enqueue! fixture/*ds* settings did expected)
        (let [saved (control-test/job did)]
          (with-redefs [events/sync! (fn [& _] (throw (ex-info "Local event failure" {})))]
            (is (thrown? Exception (handles/process-one! fixture/*ds* settings did))))
          (is (= (:head old) (:head (state did))))
          (is (= (vec (:public_key old)) (vec (:public_key (state did)))))
          (is (empty? (provision/rows "SELECT * FROM signing_key_rotations")))
          (is (= (:operation_cid saved) (:head (directory/audit! client origin did))))
          (handle-test/due!)
          (let [ensure! directory/ensure-operation!]
            (with-redefs [directory/ensure-operation!
                          (fn [& args]
                            (let [result (apply ensure! args)]
                              (handle-test/due!)
                              (with-redefs [directory/ensure-operation! ensure!]
                                (is (= :updated (handles/process-one! fixture/*ds* settings did))))
                              result))]
              (is (nil? (handles/process-one! fixture/*ds* settings did)) "Expired owner cannot install another head")))
          (is (= "completed" (:state (rotate settings did expected))))
          (is (= (vec (:next_signing_key saved)) (vec (:signing_key (state did)))))
          (is (= 2 (count (tls/posts calls))))
          (is (= 1 (count (provision/rows "SELECT * FROM signing_key_rotations")))))))))

(deftest stale-or-external-identities-cannot-be-rotated
  (let [settings (api/settings) alice (seed! settings) did (:did alice) expected (keys/public-key (:public_key (state did)))]
    (is (= "IdentityMismatch" (control-test/error #(rotate settings did (plc/did-key (crypto/keypair "ES256"))))))
    (tx #(db/execute! % "UPDATE accounts SET imported = true WHERE did = ?" did))
    (is (= "UnsupportedDID" (control-test/error #(rotate settings did expected))))
    (is (empty? (provision/rows "SELECT * FROM signing_key_rotations")))))

(deftest hosted-web-failure-rolls-back-key-head-and-events
  (let [settings (api/settings) alice (seed! settings) did (:did alice) old (state did)
        expected (keys/public-key (:public_key old))
        cursor (:seq (first (provision/rows "SELECT max(seq) AS seq FROM repo_events")))]
    (with-redefs [events/sync! (fn [& _] (throw (ex-info "Event storage unavailable" {})))]
      (is (thrown? Exception (rotate settings did expected))))
    (is (= (:head old) (:head (state did))))
    (is (= (vec (:public_key old)) (vec (:public_key (state did)))))
    (is (= (vec (:signing_key old)) (vec (:signing_key (state did)))))
    (is (empty? (provision/rows "SELECT * FROM signing_key_rotations")))
    (is (empty? (provision/rows "SELECT * FROM repo_events WHERE seq > ?" cursor)))
    (is (= "completed" (:state (rotate settings did expected))))))

(deftest invalid-replacement-material-fails-before-publication-and-keeps-writes-paused
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) alice (seed! settings) did (:did alice)
            old (state did) expected (keys/public-key (:public_key old))]
        (keys/enqueue! fixture/*ds* settings did expected)
        (tx #(db/execute! % "UPDATE handle_updates SET next_signing_key = ? WHERE did = ?" (byte-array [1 2 3]) did))
        (is (= :pending (handles/process-one! fixture/*ds* settings did)))
        (is (= "failed" (:status (control-test/job did))))
        (is (= 1 (count (tls/posts calls))) "Only initial signup was posted")
        (is (= (:head old) (:head (state did))))
        (is (= (vec (:signing_key old)) (vec (:signing_key (state did)))))
        (is (= "SigningKeyRotationPending" (control-test/error #(tx (fn [conn] (repo/state conn did))))))
        (is (empty? (provision/rows "SELECT * FROM signing_key_rotations")))))))

(deftest remote-conflict-retains-replacement-material-without-installing-it
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) alice (seed! settings) did (:did alice)
            old (state did) expected (keys/public-key (:public_key old))
            competing (submission-test/sign-update settings alice {"alsoKnownAs" ["at://outside.example.com"]})]
        (keys/enqueue! fixture/*ds* settings did expected)
        (let [saved (control-test/job did)]
          (directory/ensure-operation! client origin did competing)
          (is (= :pending (handles/process-one! fixture/*ds* settings did)))
          (is (= "failed" (:status (control-test/job did))))
          (is (= "conflict" (:last_error (control-test/job did))))
          (is (= (vec (:next_signing_key saved)) (vec (:next_signing_key (control-test/job did)))))
          (is (= (:head old) (:head (state did))))
          (is (= "SigningKeyRotationPending" (control-test/error #(tx (fn [conn] (repo/state conn did))))))
          (is (= 2 (count (tls/posts calls)))))))))

(deftest inactive-rotation-preserves-status-and-does-not-publish-sync
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (seed! settings) did (:did alice)]
        (doseq [status ["deactivated" "taken_down"]]
          (tx #(db/execute! % "UPDATE accounts SET status = ? WHERE did = ?" status did))
          (let [old (state did) cursor (:seq (first (provision/rows "SELECT max(seq) AS seq FROM repo_events")))
                result (rotate settings did (keys/public-key (:public_key old)))]
            (is (= "completed" (:state result)))
            (is (= status (:status (first (provision/rows "SELECT status FROM accounts WHERE did = ?" did)))))
            (is (= ["identity"] (mapv :event_type (provision/rows "SELECT event_type FROM repo_events WHERE seq > ? ORDER BY seq" cursor))))
            (is (= (:signingKey result) (get-in (directory/audit! client origin did) [:data "verificationMethods" "atproto"])))
            (is (not= (:head old) (:head (state did))))))))))

(deftest a-writer-waiting-on-the-account-lock-observes-newly-queued-rotation
  ;; Detects a blocked writer through PostgreSQL lock introspection
  ;; (pg_blocking_pids). SQLite has no equivalent and serializes writers.
  (when (fixture/postgres?)
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) alice (seed! settings) did (:did alice)
            old (state did) locked (promise) release (promise) pid (promise) execute! db/execute!]
        (with-redefs [db/execute! (fn [conn sql & args]
                                   (let [result (apply execute! conn sql args)]
                                     (when (.startsWith ^String sql "INSERT INTO handle_updates")
                                       (deliver locked true)
                                       (when-not (= true (deref release 10000 :timeout)) (throw (ex-info "Test timed out" {}))))
                                     result))]
          (let [rotation (future (keys/enqueue! fixture/*ds* settings did (keys/public-key (:public_key old))))]
            (try
              (is (= true (deref locked 10000 :timeout)))
              (let [writer (future (control-test/error
                                    #(tx (fn [conn]
                                           (deliver pid (:pid (first (db/query conn "SELECT pg_backend_pid() AS pid"))))
                                           (repo/apply-writes! conn settings did [{:action :create :collection "com.example.note" :rkey "late"
                                                                                  :value {"$type" "com.example.note"}}] nil)))))
                    backend (deref pid 10000 :timeout)]
                (try
                  (is (number? backend))
                  (is (loop [attempts 100]
                        (cond
                          (:blocked (first (provision/rows "SELECT cardinality(pg_blocking_pids(?)) > 0 AS blocked" backend))) true
                          (zero? attempts) false
                          :else (do (Thread/sleep 20) (recur (dec attempts))))))
                  (deliver release true)
                  (is (= "pending" (:state (deref rotation 10000 :timeout))))
                  (is (= "SigningKeyRotationPending" (deref writer 10000 :timeout)))
                  (is (= (:head old) (:head (state did))))
                  (is (empty? (provision/rows "SELECT * FROM records WHERE did = ? AND rkey = 'late'" did)))
                  (finally (deliver release true) (deref writer 10000 nil))))
              (finally (deliver release true) (deref rotation 10000 nil))))))))))
