(ns pds.sync-api-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.mst :as mst]
            [pds.repo :as repo]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(use-fixtures :each fixture/isolated-database)

(defn verify-upstream!
  ([fixture-data] (verify-upstream! "verify-proof.mjs" fixture-data))
  ([script fixture-data]
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [path (Files/createTempFile "pds-proof-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (str path) (json/write-str fixture-data))
        (let [process (.start (doto (ProcessBuilder. ["node" (str "scripts/conformance/" script) (str path)])
                               (.redirectErrorStream true)))
              finished? (.waitFor process 30 TimeUnit/SECONDS)]
          (when-not finished? (.destroyForcibly process))
          (is finished? "Upstream verification completes within 30 seconds")
          (when finished?
            (let [output (slurp (.getInputStream process))]
              (is (= 0 (.exitValue process)) output))))
        (finally (Files/deleteIfExists path)))))))

(deftest blocks-and-record-proofs-over-http
  (let [settings (api/settings)
        alice (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "test-password"})
        bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})
        did (:did alice) collection "com.example.record"
        write #(db/transact! fixture/*ds* (fn [conn] (repo/apply-writes! conn settings %1 %2 nil)))
        writes (mapv (fn [n] {:action :create :collection collection :rkey (str n)
                              :value {"$type" collection "n" n}}) (range 30))
        original (write did writes)
        old-cid (get-in original [:results 0 :cid])
        _ (write did [{:action :delete :collection collection :rkey "0"}])
        foreign (get-in (write (:did bob) [{:action :create :collection collection :rkey "private"
                                           :value {"$type" collection "foreign" true}}]) [:results 0 :cid])
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [get! #(api/xrpc client port "GET" % nil nil)
              exported (get! (str "com.atproto.sync.getRepo?did=" did))
              full (car/decode (:raw exported))
              head (first (:roots full))
              blocks (get! (str "com.atproto.sync.getBlocks?did=" did "&cids=" head "&cids=" old-cid))
              decoded (car/decode (:raw blocks))
              proofs (mapv (fn [key]
                             (let [response (get! (str "com.atproto.sync.getRecord?did=" did "&collection=" collection "&rkey=" key))
                                   proof (car/decode (:raw response))
                                   root (:cid (get (codec/decode (get-in proof [:blocks head])) "data"))
                                   result (mst/proof root (str collection "/" key) (:blocks proof))]
                               (is (= 200 (:status response)))
                               (is (= [head] (:roots proof)))
                               (is (< (count (:blocks proof)) (count (:blocks full))))
                               (is (= (get-in original [:results (Integer/parseInt key) :cid])
                                      (when (not= "0" key) (:cid result))))
                               {:car (crypto/b64 (:raw response)) :collection collection :rkey key :cid (:cid result)})) ["1" "12" "29"])
              missing (get! (str "com.atproto.sync.getRecord?did=" did "&collection=" collection "&rkey=0"))]
          (is (= 200 (:status blocks) (:status missing)))
          (is (= [] (:roots decoded)))
          (is (= #{head old-cid} (set (keys (:blocks decoded)))))
          (doseq [query [(str "did=" did "&cids=" foreign) (str "did=" did)
                         (str "did=" did "&cids=invalid") (str "did=" did "&did=" did "&cids=" head)]]
            (is (= 400 (:status (get! (str "com.atproto.sync.getBlocks?" query))))))
          (with-open [conn (db/connection fixture/*ds*)]
            (let [key (:public_key (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" did)))]
              (verify-upstream! {:did did :didKey (str "did:key:" (crypto/multikey "ES256" key))
                                 :repo (crypto/b64 (:raw exported)) :recordCount 29
                                 :blocks (crypto/b64 (:raw blocks)) :blockCids [head old-cid]
                                 :proofs (conj proofs {:car (crypto/b64 (:raw missing)) :collection collection :rkey "0" :cid nil})})))
          (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.server.deactivateAccount" {} (:accessJwt alice)))))
          (is (= 400 (:status (get! (str "com.atproto.sync.getBlocks?did=" did "&cids=" old-cid)))))
          (is (= 400 (:status (get! (str "com.atproto.sync.getRecord?did=" did "&collection=" collection "&rkey=1")))))))
      (finally ((:stop! server))))))

(deftest ownership-upgrade-retains-history-without-following-record-links
  (let [all-migrations db/migrations]
    (with-redefs [db/migrations (vec (take 11 all-migrations))]
      (fixture/isolated-database
        (fn []
          ;; Model the earlier schema: commit! now writes ownership too, so create
          ;; the account/repository rows and signed tree snapshots explicitly.
          (let [ds fixture/*ds* settings (api/settings)
                did "did:web:legacy.example.com"
                keypair (crypto/keypair)
                foreign (codec/encode {"$type" "com.example.foreign"}) foreign-cid (codec/cid foreign)
                record (codec/encode {"$type" "com.example.record" "n" 1 "ref" (codec/link foreign-cid)})
                old-cid (codec/cid record)
                tree (mst/build {"com.example.record/one" old-cid})
                unsigned {"did" did "version" 3 "rev" "3k7k3a7u4hk2s" "prev" nil "data" (codec/link (:root tree))}
                commit (codec/encode (assoc unsigned "sig" (crypto/sign "ES256" (:private keypair) (codec/encode unsigned))))
                head (codec/cid commit)
                empty-tree (mst/build {})
                current-unsigned (assoc unsigned "rev" "3k7k3a7u4hk2t" "data" (codec/link (:root empty-tree)))
                current (codec/encode (assoc current-unsigned "sig" (crypto/sign "ES256" (:private keypair) (codec/encode current-unsigned))))
                current-head (codec/cid current)]
            (db/transact! ds (fn [conn]
              (db/execute! conn "INSERT INTO accounts(did, handle, email, password_hash) VALUES (?, 'legacy.example.com', 'legacy@example.com', 'unused')" did)
              (doseq [[_ data] (assoc (merge (:blocks tree) (:blocks empty-tree)) head commit current-head current old-cid record foreign-cid foreign)]
                (repo/block! conn data))
              (db/execute! conn "INSERT INTO repositories(did, head, rev, signing_key, public_key) VALUES (?, ?, ?, ?, ?)"
                           did current-head "3k7k3a7u4hk2t" (crypto/seal (:master-key settings) did (:private keypair)) (:public keypair))
              (db/execute! conn "INSERT INTO repo_events(did, rev, commit_cid) VALUES (?, ?, ?)" did "3k7k3a7u4hk2s" head)))
            (with-redefs [db/migrations all-migrations]
              (is (true? (db/migrate! ds))))
            (with-open [conn (db/connection ds)]
              (let [event (first (db/query conn "SELECT event_type, payload FROM repo_events WHERE did = ?" did))
                    payload (codec/decode (:payload event))]
                (is (= "sync" (:event_type event)))
                (is (= did (get payload "did"))))
              (is (= (conj (set (keys (:blocks tree))) old-cid head current-head (:root empty-tree))
                     (set (map :cid (db/query conn "SELECT cid FROM repo_block_owners WHERE did = ?" did))))))))))))

(deftest repositories-enumerate-by-record-collection
  (let [settings (api/settings)
        create #(accounts/create! fixture/*ds* settings {"handle" (str % ".example.com") "email" (str % "@example.com") "password" "test-password"})
        alice (create "alice") bob (create "bob") carol (create "carol")
        write #(db/transact! fixture/*ds* (fn [conn] (repo/apply-writes! conn settings %1 %2 nil)))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (doseq [account [alice bob carol]]
      (write (:did account) [{:action :create :collection "com.example.note" :rkey "self"
                              :value {"$type" "com.example.note"}}]))
    (write (:did alice) [{:action :create :collection "com.example.other" :rkey "self"
                          :value {"$type" "com.example.other"}}
                         {:action :create :collection "com.example.note" :rkey "second"
                          :value {"$type" "com.example.note"}}])
    (db/transact! fixture/*ds* #(db/execute! % "UPDATE accounts SET status = 'deactivated' WHERE did = ?" (:did carol)))
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client (:port server) "GET" % nil nil)
              repos #(get-in (call (str "com.atproto.sync.listReposByCollection?" %)) [:body "repos"])
              active (sort [(:did alice) (:did bob)])]
          (is (= 400 (:status (call "com.atproto.sync.listReposByCollection"))))
          (is (= 400 (:status (call "com.atproto.sync.listReposByCollection?collection=not-an-nsid"))))
          (is (= 400 (:status (call "com.atproto.sync.listReposByCollection?collection=com.example.note&cursor=bad"))))
          (is (= 400 (:status (call "com.atproto.sync.listReposByCollection?collection=com.example.note&limit=2001"))))
          (is (= [] (repos "collection=com.example.missing")))
          (is (= [(:did alice)] (mapv #(get % "did") (repos "collection=com.example.other"))))
          (let [result (:body (call "com.atproto.sync.listReposByCollection?collection=com.example.note"))]
            (is (= active (mapv #(get % "did") (get result "repos"))) "Duplicate records list once; inactive repositories are excluded")
            (is (nil? (get result "cursor"))))
          (let [first-page (:body (call "com.atproto.sync.listReposByCollection?collection=com.example.note&limit=1"))
                second-page (:body (call (str "com.atproto.sync.listReposByCollection?collection=com.example.note&limit=1&cursor="
                                              (get first-page "cursor"))))]
            (is (= active (mapv #(get-in % ["repos" 0 "did"]) [first-page second-page])))
            (is (nil? (get second-page "cursor"))))))
      (finally ((:stop! server))))))
