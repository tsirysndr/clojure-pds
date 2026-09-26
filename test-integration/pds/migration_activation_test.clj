(ns pds.migration-activation-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.walk :as walk]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.events :as events]
            [pds.handles :as handles]
            [pds.http :as http]
            [pds.identity :as identity]
            [pds.migration :as migration]
            [pds.migration-test :as preparation]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as directory-test]
            [pds.plc-provision-test :as provision :refer [rows scalar]]
            [pds.plc-test :as plc-test]
            [pds.protocol.codec :as codec]
            [pds.protocol.repository :as repository]
            [pds.repo-import :as repo-import]
            [pds.repo-import-test :as imports]
            [pds.server-api-test :as api]
            [pds.service-auth-test :refer [token-request error]])
  (:import [java.net.http HttpClient]
           [java.util.concurrent CountDownLatch TimeUnit]))
(use-fixtures :each fixture/isolated-database)

(defn transfer! [settings account source]
  (let [f (imports/repo-car (:did account) (:key source) {"com.example.record/a" {"text" "migrated"}})]
    (repo-import/import! fixture/*ds* settings (imports/import-request (:accessJwt account) (:car f)))))
(defn destination-doc [settings account]
  (walk/stringify-keys (accounts/did-document settings account (:public (imports/local-key settings (:did account))))))
(defn web-settings [settings document]
  (assoc settings :fetch (fn [_ _] {:status 200 :body (codec/utf8 (json/write-str @document))})))

(deftest web-activation-checks-remote-credentials-and-publishes-the-imported-repository
  (let [settings (api/settings) {:keys [account source]} (imports/prepare! settings) did (:did account)
        expected (destination-doc settings account) document (atom expected) settings (web-settings settings document)
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [activate #(api/xrpc client (:port server) "POST" "com.atproto.server.activateAccount" nil (:accessJwt account))]
          (is (= "MigrationIncomplete" (get-in (activate) [:body "error"])))
          (transfer! settings account source)
          (doseq [bad [(assoc-in expected ["service" 0 "serviceEndpoint"] "https://other.example.net")
                       (assoc expected "alsoKnownAs" ["at://wrong.example.com"])
                       (assoc-in expected ["verificationMethod" 0 "publicKeyMultibase"] (crypto/multikey "ES256" (:public (crypto/keypair))))]]
            (reset! document bad)
            (is (= "IdentityMismatch" (get-in (activate) [:body "error"]))))
          (is (= 0 (scalar "SELECT count(*) AS n FROM repo_events")))
          (reset! document expected)
          (let [before (imports/state)]
            (with-redefs [events/sync! (fn [& _] (throw (ex-info "Injected event failure" {})))]
              (is (= 500 (:status (activate)))))
            (is (= before (imports/state)))
            (is (= "deactivated" (:status (first (rows "SELECT status FROM accounts WHERE did = ?" did))))))
          (is (= 200 (:status (activate))))
          (is (= 0 (scalar "SELECT count(*) AS n FROM account_imports")))
          (is (= "active" (:status (first (rows "SELECT status FROM accounts WHERE did = ?" did)))))
          (is (= ["identity" "account" "sync"] (mapv :event_type (rows "SELECT event_type FROM repo_events ORDER BY seq"))))
          (let [export (api/xrpc client (:port server) "GET" (str "com.atproto.sync.getRepo?did=" did) nil nil)
                verified (repository/verify-car (:raw export) did (imports/local-key settings did))]
            (is (= 200 (:status export)))
            (is (= ["a"] (mapv :rkey (:paths verified)))))
          (is (= 200 (:status (activate))))
          (is (= 3 (scalar "SELECT count(*) AS n FROM repo_events")))))
      (finally ((:stop! server))))))

(deftest plc-activation-reconciles-an-externally-submitted-credential-transfer
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin) resolver (identity/resolver settings)
            source (preparation/source! client origin)
            account (migration/create! fixture/*ds* settings resolver
                       (token-request (preparation/authorization settings source)) (preparation/input source))
            did (:did account) request (token-request (:accessJwt account))
            activate #(migration/activate! fixture/*ds* settings resolver request)
            recommended (walk/stringify-keys (db/transact! fixture/*ds* #(handles/recommended % settings (assoc account :access-scope "com.atproto.access"))))]
        (transfer! settings account source)
        (is (= "IdentityMismatch" (error activate)))
        (let [without-rotation (plc-test/update-op (:operation source) (:rotation source) (dissoc recommended "rotationKeys"))
              operation (plc-test/update-op without-rotation (:rotation source) recommended)]
          (directory/ensure-operation! client origin did without-rotation)
          (is (= "IdentityMismatch" (error activate)) "Destination must retain PLC rotation authority")
          (directory/ensure-operation! client origin did operation)
          (is (= "prepared" (:status (first (rows "SELECT status FROM plc_identities WHERE did = ?" did)))))
          (is (nil? (activate)))
          (is (= 3 (count (directory-test/posts calls))) "Activation performs no directory mutation")
          (is (= [{:status "ready" :operation_cid (plc/operation-cid operation)}]
                 (rows "SELECT status, operation_cid FROM plc_identities WHERE did = ?" did)))
          (is (= "active" (:status (first (rows "SELECT status FROM accounts WHERE did = ?" did)))))
          (is (= ["identity" "account" "sync"] (mapv :event_type (rows "SELECT event_type FROM repo_events ORDER BY seq"))))
          (is (= (plc/did-document (:data (directory/audit! client origin did)))
                 (identity/resolve-did! resolver did))))))))

(deftest activation-reauthenticates-and-rejects-content-changes-during-resolution
  (let [base (api/settings) {:keys [account source]} (imports/prepare! base) did (:did account)
        document (atom (destination-doc base account)) settings (web-settings base document)
        resolver (identity/resolver settings) resolve! identity/resolve-did!
        request (token-request (:accessJwt account)) activate #(migration/activate! fixture/*ds* settings resolver request)]
    (transfer! settings account source)
    (with-redefs [identity/resolve-did! (fn [& args]
                                        (let [doc (apply resolve! args)]
                                          (transfer! settings account source)
                                          doc))]
      (is (= "InvalidSwap" (error activate))))
    (is (= 1 (scalar "SELECT count(*) AS n FROM account_imports")))
    (is (= 0 (scalar "SELECT count(*) AS n FROM repo_events")))
    (with-redefs [identity/resolve-did! (fn [& args]
                                        (let [doc (apply resolve! args)]
                                          (db/transact! fixture/*ds* #(db/execute! % "UPDATE sessions SET revoked = true WHERE did = ?" did))
                                          doc))]
      (is (= "InvalidToken" (error activate))))
    (is (= "deactivated" (:status (first (rows "SELECT status FROM accounts WHERE did = ?" did)))))
    (is (= 0 (scalar "SELECT count(*) AS n FROM repo_events")))))

(deftest concurrent-activation-publishes-only-one-announcement
  (let [base (api/settings) {:keys [account source]} (imports/prepare! base)
        document (atom (destination-doc base account)) settings (web-settings base document)
        resolver (identity/resolver settings) resolve! identity/resolve-did!
        request (token-request (:accessJwt account)) arrived (CountDownLatch. 2) release (CountDownLatch. 1)]
    (transfer! settings account source)
    (with-redefs [identity/resolve-did! (fn [& args]
                                        (.countDown arrived)
                                        (when-not (.await release 15 TimeUnit/SECONDS) (throw (ex-info "Barrier timeout" {})))
                                        (apply resolve! args))]
      (let [tasks (mapv (fn [_] (future (migration/activate! fixture/*ds* settings resolver request))) (range 2))]
        (try (is (.await arrived 10 TimeUnit/SECONDS)) (finally (.countDown release)))
        (is (= [nil nil] (mapv #(deref % 15000 :timeout) tasks)))))
    (is (= ["identity" "account" "sync"] (mapv :event_type (rows "SELECT event_type FROM repo_events ORDER BY seq"))))))
