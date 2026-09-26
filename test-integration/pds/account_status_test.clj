(ns pds.account-status-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.walk :as walk]
            [pds.account-status :as status]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.blob-refs-test :refer [blob]]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.handles :as handles]
            [pds.http :as http]
            [pds.identity :as identity]
            [pds.migration :as migration]
            [pds.migration-activation-test :as activation]
            [pds.migration-test :as preparation]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as directory-test]
            [pds.plc-provision-test :as provision :refer [rows]]
            [pds.plc-test :as plc-test]
            [pds.protocol.codec :as codec]
            [pds.repo-import :as repo-import]
            [pds.repo-import-test :as imports]
            [pds.s3-api-test :refer [upload]]
            [pds.server-api-test :as api]
            [pds.service-auth-test :refer [token-request error document]])
  (:import [java.net.http HttpClient]))
(use-fixtures :each fixture/isolated-database)

(def fields #{"activated" "validDid" "repoCommit" "repoRev" "repoBlocks" "indexedRecords" "privateStateValues" "expectedBlobs" "importedBlobs"})

(deftest status-tracks-the-complete-destination-transfer-over-http
  (let [base (api/settings) {:keys [account source]} (imports/prepare! base) did (:did account)
        doc (atom (document did (:key source))) settings (activation/web-settings base doc)
        server (http/start! settings (app/handler settings fixture/*ds*))
        bytes (byte-array [1 2 3]) record {"$type" "com.example.file" "files" [(blob bytes) (blob bytes)]}]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client (:port server) %1 %2 %3 (:accessJwt account))
              check #(get (call "GET" "com.atproto.server.checkAccountStatus" nil) :body)
              initial (check)]
          (is (= fields (set (keys initial))))
          (is (= {"activated" false "validDid" false "indexedRecords" 0 "repoBlocks" 2 "privateStateValues" 0 "expectedBlobs" 0 "importedBlobs" 0}
                 (dissoc initial "repoCommit" "repoRev")))
          (let [f (imports/repo-car did (:key source) {"com.example.file/a" record "com.example.file/b" record})]
            (repo-import/import! fixture/*ds* settings (imports/import-request (:accessJwt account) (:car f))))
          (let [imported (check) local (first (rows "SELECT head, rev FROM repositories WHERE did = ?" did))]
            (is (= 2 (get imported "indexedRecords")))
            (is (= 1 (get imported "expectedBlobs")))
            (is (= 0 (get imported "importedBlobs")))
            (is (= (:head local) (get imported "repoCommit")))
            (is (= (:rev local) (get imported "repoRev")))
            (is (= (:n (first (rows "SELECT count(*) AS n FROM repo_block_owners WHERE did = ?" did))) (get imported "repoBlocks"))))
          (is (= 200 (:status (upload client (:port server) (:accessJwt account) bytes "image/png"))))
          (is (= 1 (get (check) "importedBlobs")))
          (reset! doc (activation/destination-doc settings account))
          (is (true? (get (check) "validDid")))
          (is (false? (get (check) "activated")))
          (is (= 200 (:status (call "POST" "com.atproto.server.activateAccount" nil))))
          (is (true? (get (check) "activated")))
          ;; Active app sessions can inspect their own progress but cannot choose
          ;; an account with a query parameter. All counters stay account scoped.
          (let [bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "bob-password"})
                password (get-in (call "POST" "com.atproto.server.createAppPassword" {"name" "reader"}) [:body "password"])
                session (accounts/login! fixture/*ds* settings {"identifier" did "password" password})
                app-check #(api/xrpc client (:port server) "GET" (str "com.atproto.server.checkAccountStatus?did=" (:did bob)) nil (:accessJwt session))]
            (is (= (check) (:body (app-check))))
            (is (= 401 (:status (api/xrpc client (:port server) "GET" "com.atproto.server.checkAccountStatus" nil nil))))
            (is (= 200 (:status (call "POST" "com.atproto.server.deactivateAccount" {}))))
            (is (= 401 (:status (app-check))))
            (is (false? (get (check) "activated"))))))
      (finally ((:stop! server))))))

(deftest fresh-web-verification-failures-do-not-hide-local-counts
  (let [base (api/settings) account (imports/local! base) did (:did account)
        correct (activation/destination-doc base account) doc (atom correct) calls (atom 0)
        settings (assoc base :fetch (fn [_ _] (swap! calls inc) {:status 200 :body (codec/utf8 (json/write-str @doc))}))
        handler (app/handler settings fixture/*ds*) request (assoc (token-request (:accessJwt account))
                                                                :request-method :get :uri "/xrpc/com.atproto.server.checkAccountStatus")
        check #(json/read-str (:body (handler request)))]
    (is (true? (get (check) "validDid")))
    (is (= "no-store" (get-in (handler request) [:headers "Cache-Control"])))
    ;; validDid describes credentials, not handle binding or transfer completion.
    (reset! doc (assoc correct "alsoKnownAs" ["at://different.example.net"]))
    (is (true? (get (check) "validDid")))
    (doseq [bad [(assoc-in correct ["service" 0 "serviceEndpoint"] "https://elsewhere.example.net")
                 (assoc correct "verificationMethod" []) (assoc correct "id" "did:web:other.example.net")]]
      (reset! doc bad)
      (let [result (check)]
        (is (false? (get result "validDid")))
        (is (= 0 (get result "indexedRecords")))
        (is (true? (get result "activated")))))
    (is (= 6 @calls))
    (let [unavailable (assoc settings :fetch (fn [& _] (throw (ex-info "Offline" {}))))]
      (is (false? (:validDid (status/check! fixture/*ds* unavailable (identity/resolver unavailable) request)))))
    (is (= [{:status "active"}] (rows "SELECT status FROM accounts WHERE did = ?" did)))))

(deftest plc-status-verifies-current-audit-and-destination-rotation-authority
  (directory-test/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) resolver (identity/resolver settings)
            source (preparation/source! client origin)
            account (migration/create! fixture/*ds* settings resolver
                       (token-request (preparation/authorization settings source)) (preparation/input source))
            request (token-request (:accessJwt account)) check #(status/check! fixture/*ds* settings resolver request)
            rec (walk/stringify-keys (db/transact! fixture/*ds* #(handles/recommended % settings account)))
            no-rotation (plc-test/update-op (:operation source) (:rotation source) (dissoc rec "rotationKeys"))
            transfer (plc-test/update-op no-rotation (:rotation source) rec)]
        (is (false? (:validDid (check))))
        (directory/ensure-operation! client origin (:did source) no-rotation)
        (is (false? (:validDid (check))))
        (directory/ensure-operation! client origin (:did source) transfer)
        (is (true? (:validDid (check))))
        (is (false? (:activated (check))))
        (is (= [{:status "prepared"}] (rows "SELECT status FROM plc_identities")) "Status lookup does not reconcile or activate")
        (is (false? (:repository_imported (first (rows "SELECT repository_imported FROM account_imports")))))))))

(deftest status-reauthenticates-and-does-not-reuse-a-verdict-for-changed-keys
  (let [base (api/settings) account (imports/local! base) did (:did account)
        key (imports/local-key base did) doc (atom (activation/destination-doc base account))
        settings (activation/web-settings base doc) resolver (identity/resolver settings)
        request (token-request (:accessJwt account)) check #(status/check! fixture/*ds* settings resolver request)
        resolve! identity/resolve-did!]
    (with-redefs [identity/resolve-did! (fn [& args]
                                        (let [value (apply resolve! args)]
                                          (db/transact! fixture/*ds* #(db/execute! % "UPDATE repositories SET public_key = ? WHERE did = ?" (:public (crypto/keypair)) did))
                                          value))]
      (is (false? (:validDid (check)))))
    (db/transact! fixture/*ds* #(db/execute! % "UPDATE repositories SET public_key = ? WHERE did = ?" (:public key) did))
    (with-redefs [identity/resolve-did! (fn [& args]
                                        (let [value (apply resolve! args)]
                                          (db/transact! fixture/*ds* #(db/execute! % "UPDATE sessions SET revoked = true WHERE did = ?" did))
                                          value))]
      (is (= "InvalidToken" (error check))))))
