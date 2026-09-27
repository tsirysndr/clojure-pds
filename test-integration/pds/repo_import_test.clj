(ns pds.repo-import-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.app-passwords :as app-passwords]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.events :as events]
            [pds.http :as http]
            [pds.identity :as identity]
            [pds.migration :as migration]
            [pds.migration-test :as migration-test]
            [pds.plc-provision-test :refer [rows scalar]]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.repository :as repository]
            [pds.repo :as repo]
            [pds.repo-import :as repo-import]
            [pds.repository-test :as repository-test]
            [pds.server-api-test :as api]
            [pds.service-auth-test :refer [document token-request error]])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent CountDownLatch TimeUnit]))
(use-fixtures :each fixture/isolated-database)

(defn local! [settings]
  (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "test-password"}))
(defn local-key [settings did]
  (let [row (first (rows "SELECT * FROM repositories WHERE did = ?" did))]
    {:algorithm "ES256" :public (:public_key row) :private (crypto/unseal (:master-key settings) did (:signing_key row))}))
(defn repo-car [did key values]
  (repository-test/signed-car key (repository-test/fixture key values) {"did" did}))
(defn import-request [token bytes]
  {:request-method :post :uri "/xrpc/com.atproto.repo.importRepo"
   :headers {"authorization" (str "Bearer " token) "content-type" "application/vnd.ipld.car"}
   :body (ByteArrayInputStream. bytes)})
(defn state []
  [(rows "SELECT did, head, rev FROM repositories ORDER BY did")
   (rows "SELECT * FROM records ORDER BY did, collection, rkey")
   (rows "SELECT cid FROM repo_blocks ORDER BY cid")
   (rows "SELECT did, cid FROM repo_block_owners ORDER BY did, cid")
   (rows "SELECT seq FROM repo_events ORDER BY seq")
   (rows "SELECT did, repository_imported FROM account_imports ORDER BY did")])
(defn export [did] (db/transact! fixture/*ds* #(repo/export-car % did)))
(defn prepare! [settings]
  (let [source {:did "did:web:remote.example.net" :key (crypto/keypair "ES256K")}
        doc (document (:did source) (:key source))
        settings (assoc settings :fetch (fn [_ _] {:status 200 :body (codec/utf8 (json/write-str doc))}))]
    {:source source
     :account (migration/create! fixture/*ds* settings (identity/resolver settings)
                                 (token-request (migration-test/authorization settings source)) (migration-test/input source))}))

(deftest full-backup-import-over-http-replaces-records-and-publishes-sync
  (let [settings (api/settings) account (local! settings) did (:did account) key (local-key settings did)
        values {"com.example.record/a" {"$type" "com.example.record" "text" "restored"}}
        f (repo-car did key values) server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (db/transact! fixture/*ds* #(repo/apply-writes! % settings did [{:action :create :collection "com.example.record" :rkey "removed"
                                                                      :value {"$type" "com.example.record"}}] nil))
      (let [old (first (rows "SELECT head, rev FROM repositories WHERE did = ?" did))
            before (scalar "SELECT count(*) AS n FROM repo_events")]
        (with-open [client (HttpClient/newHttpClient)]
          (let [request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" (:port server) "/xrpc/com.atproto.repo.importRepo")))
                            (.timeout (Duration/ofSeconds 20))
                            (.header "Authorization" (str "Bearer " (:accessJwt account)))
                            (.header "Content-Type" "application/vnd.ipld.car")
                            (.POST (HttpRequest$BodyPublishers/ofByteArray (:car f))) .build)
                response (.send client request (HttpResponse$BodyHandlers/ofByteArray))
                new (repository/verify-car (export did) did key)
                event (last (rows "SELECT event_type, payload FROM repo_events ORDER BY seq"))
                payload (codec/decode (:payload event))]
            (is (= 200 (.statusCode response)))
            (is (= 0 (alength ^bytes (.body response))))
            (is (= ["a"] (mapv :rkey (:paths new))))
            (is (pos? (compare (:rev new) (:rev old))))
            (is (pos? (compare (:rev new) (get-in f [:commit "rev"]))))
            (is (= (inc before) (scalar "SELECT count(*) AS n FROM repo_events")))
            (is (= "sync" (:event_type event)))
            (is (= (:rev new) (get payload "rev")))
            (is (= [(:head new)] (:roots (car/decode (get payload "blocks")))))
            (is (= 200 (:status (api/xrpc client (:port server) "GET" (str "com.atproto.repo.getRecord?repo=" did "&collection=com.example.record&rkey=a") nil nil))))
            (repo-import/import! fixture/*ds* settings (import-request (:accessJwt account) (:car (repo-car did key {}))))
            (is (empty? (:paths (repository/verify-car (export did) did key)))))))
      (finally ((:stop! server))))))

(deftest inactive-destination-import-uses-retained-source-key-and-remains-private
  (let [settings (api/settings) {:keys [account source]} (prepare! settings) did (:did account)
        data {"$type" "com.example.record" "text" "migrated"}
        f (repo-car did (:key source) {"com.example.record/a" data}) handler (app/handler settings fixture/*ds*)
        wrong (repo-car did (crypto/keypair "ES256K") {"com.example.record/a" data}) before (state)]
    (is (= 400 (:status (handler (import-request (:accessJwt account) (:car wrong))))))
    (is (= before (state)))
    (let [execute! db/execute!]
      (with-redefs [db/execute! (fn [conn sql & args]
                                 (let [result (apply execute! conn sql args)]
                                   (when (.startsWith ^String sql "UPDATE account_imports")
                                     (throw (ex-info "Injected preparation failure" {})))
                                   result))]
        (is (= 500 (:status (handler (import-request (:accessJwt account) (:car f))))))))
    (is (= before (state)) "Prepared import progress rolls back with content")
    (is (= 200 (:status (handler (import-request (:accessJwt account) (:car f))))))
    (is (true? (:repository_imported (first (rows "SELECT repository_imported FROM account_imports WHERE did = ?" did)))))
    (is (= "deactivated" (:status (first (rows "SELECT status FROM accounts WHERE did = ?" did)))))
    (is (= 0 (scalar "SELECT count(*) AS n FROM repo_events")))
    (is (= ["a"] (mapv :rkey (:paths (repository/verify-car (export did) did (local-key settings did))))))
    (is (= 400 (:status (handler {:request-method :get :uri "/xrpc/com.atproto.sync.getRepo" :query-string (str "did=" did)}))))
    (is (= "MigrationIncomplete" (error #(db/transact! fixture/*ds* (fn [conn] (accounts/activate! conn (assoc account :access-scope "com.atproto.access")))))))))

(deftest import-authorization-bounds-and-transaction-rollback
  (let [settings (api/settings) account (local! settings) did (:did account) key (local-key settings did)
        f (repo-car did key {"com.example.record/a" {"hello" true}}) handler (app/handler settings fixture/*ds*)
        primary (assoc account :access-scope "com.atproto.access")
        password (db/transact! fixture/*ds* #(app-passwords/create! % settings primary {"name" "limited"}))
        app-session (accounts/login! fixture/*ds* settings {"identifier" did "password" (:password password)}) before (state)]
    (is (= 401 (:status (handler (assoc (import-request "invalid" (:car f)) :body (proxy [InputStream] [] (read [] (throw (Exception. "Must not read")))))))))
    (is (= 403 (:status (handler (import-request (:accessJwt app-session) (:car f))))))
    (is (= 415 (:status (handler (assoc-in (import-request (:accessJwt account) (:car f)) [:headers "content-type"] "application/json")))))
    (with-redefs [repo-import/max-size 8]
      (is (= 413 (:status (handler (import-request (:accessJwt account) (:car f)))))))
    (is (= 400 (:status (handler (import-request (:accessJwt account) (byte-array [1 2 3]))))))
    (let [cross (repo-car "did:web:another.example.net" key {})]
      (is (= 400 (:status (handler (import-request (:accessJwt account) (:car cross)))))))
    (is (= before (state)))
    (with-redefs [events/sync! (fn [& _] (throw (ex-info "Injected event failure" {})))]
      (is (= 500 (:status (handler (import-request (:accessJwt account) (:car f)))))))
    (is (= before (state)) "Records, ownership, blocks, head and events roll back together")
    (is (= 200 (:status (handler (import-request (:accessJwt account) (:car f))))))))

(deftest import-excludes-unrelated-blocks-and-reauthenticates-after-parsing
  (let [settings (api/settings) account (local! settings) did (:did account) key (local-key settings did)
        foreign-value {"$type" "com.example.record" "foreign" true}
        foreign (codec/encode foreign-value) foreign-cid (codec/cid foreign)
        f (repo-car did key {"com.example.record/a" {"reference" (codec/link foreign-cid)}})
        bytes (car/encode (:head f) (assoc (:blocks f) foreign-cid foreign))
        handler (app/handler settings fixture/*ds*) verify repository/verify-blocks
        bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})]
    (db/transact! fixture/*ds* #(repo/apply-writes! % settings (:did bob) [{:action :create :collection "com.example.record" :rkey "a" :value foreign-value}] nil))
    (is (= 200 (:status (handler (import-request (:accessJwt account) bytes)))))
    (is (= 1 (:n (first (rows "SELECT count(*) AS n FROM repo_blocks WHERE cid = ?" foreign-cid)))))
    (is (= [{:did (:did bob)}] (rows "SELECT did FROM repo_block_owners WHERE cid = ?" foreign-cid)))
    (let [before (state)]
      (with-redefs [repository/verify-blocks (fn [& args]
                                           (let [result (apply verify args)]
                                             (db/transact! fixture/*ds* #(db/execute! % "UPDATE sessions SET revoked = true WHERE did = ?" did))
                                             result))]
        (is (= 401 (:status (handler (import-request (:accessJwt account) bytes))))))
      (is (= before (state))))))

(deftest concurrent-imports-detect-conflicts-and-bound-staged-work
  (let [settings (api/settings) account (local! settings) did (:did account) key (local-key settings did)
        f (repo-car did key {"com.example.record/a" {}}) handler (app/handler settings fixture/*ds*)
        verify repository/verify-blocks arrived (CountDownLatch. 2) release (CountDownLatch. 1)]
    (with-redefs [repository/verify-blocks (fn [& args]
                                         (.countDown arrived)
                                         (when-not (.await release 15 TimeUnit/SECONDS) (throw (ex-info "Barrier timed out" {})))
                                         (apply verify args))]
      (let [tasks (mapv (fn [_] (future (handler (import-request (:accessJwt account) (:car f))))) (range 2))]
        (try
          (is (.await arrived 10 TimeUnit/SECONDS))
          (is (= 503 (:status (handler (import-request (:accessJwt account) (:car f))))))
          (finally (.countDown release)))
        (is (= [200 409] (sort (mapv #(:status (deref % 15000 {:status :timeout})) tasks))))))
    (is (= 1 (scalar "SELECT count(*) AS n FROM repo_events WHERE event_type = 'sync'")))
    (is (= 200 (:status (handler (import-request (:accessJwt account) (:car f))))))))
