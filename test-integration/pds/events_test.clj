(ns pds.events-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]
            [pds.server-api-test :as api]
            [pds.sync-api-test :as sync-test]))

(use-fixtures :each fixture/isolated-database)

(defn write [action key n]
  {:action action :collection "com.example.record" :rkey (str key)
   :value {"$type" "com.example.record" "n" n}})

(deftest durable-commit-diffs-and-inductive-proofs
  (let [settings (api/settings)
        account (accounts/create! fixture/*ds* settings {"handle" "events.example.com" "email" "events@example.com" "password" "test-password"})
        did (:did account)
        apply! #(db/transact! fixture/*ds* (fn [conn] (repo/apply-writes! conn settings did % nil)))
        batches [(mapv #(write :create % %) (range 80))
                 (mapv #(write :delete % nil) (range 0 80 3))
                 [(write :update 1 1000) (write :create "new" 1001) (write :delete 2 nil)]
                 [(write :put 1 1000) (write :delete "absent" nil)]
                 (mapv #(write :put % (+ 100 %)) (range 20))]]
    (doseq [batch batches] (apply! batch))
    (with-open [conn (db/connection fixture/*ds*)]
      (let [rows (db/query conn "SELECT * FROM repo_events WHERE did = ? ORDER BY seq" did)
            payloads (mapv #(codec/decode (:payload %) 5000000) rows)
            key (:public_key (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" did)))]
        (is (= 6 (count rows)))
        (is (every? #(= "commit" (:event_type %)) rows))
        (is (= [0 80 27 3 0 20] (mapv #(count (get % "ops")) payloads)))
        (doseq [[row event] (map vector rows payloads)]
          (is (= did (get event "repo")))
          (is (= (:rev row) (get event "rev")))
          (is (= [(:commit_cid row)] (:roots (car/decode (get event "blocks"))))))
        (sync-test/verify-upstream! "verify-events.mjs"
          {:did did :didKey (str "did:key:" (crypto/multikey "ES256" key))
           :events (mapv (fn [event]
                           {:rev (get event "rev") :since (get event "since") :prevData (:cid (get event "prevData"))
                            :blocks (crypto/b64 (get event "blocks"))
                            :ops (mapv (fn [op] {:action (get op "action") :path (get op "path") :cid (:cid (get op "cid"))
                                                :prev (:cid (get op "prev"))}) (get event "ops"))}) payloads)})))))

(deftest oversized-diffs-roll-back-the-entire-write
  (let [settings (api/settings)
        account (accounts/create! fixture/*ds* settings {"handle" "large.example.com" "email" "large@example.com" "password" "test-password"})
        did (:did account)
        snapshot #(with-open [conn (db/connection fixture/*ds*)]
                    [(db/query conn "SELECT head, rev FROM repositories WHERE did = ?" did)
                     (db/query conn "SELECT count(*) AS n FROM repo_blocks")
                     (db/query conn "SELECT count(*) AS n FROM repo_events")
                     (db/query conn "SELECT count(*) AS n FROM repo_block_owners")])
        before (snapshot) text (apply str (repeat 750000 "x"))]
    (is (= 413 (try (db/transact! fixture/*ds*
                      #(repo/apply-writes! % settings did
                         (mapv (fn [i] (assoc (write :create i i) :value {"$type" "com.example.record" "n" i "text" text})) (range 3)) nil))
                    nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))))
    (is (= before (snapshot)))
    (with-open [conn (db/connection fixture/*ds*)]
      (is (empty? (db/query conn "SELECT * FROM records WHERE did = ?" did))))
    ;; Events may exceed the default 1 MiB block decoder bound while staying
    ;; within their own 2,000,000-byte CAR limit.
    (db/transact! fixture/*ds*
      #(repo/apply-writes! % settings did
         (mapv (fn [i] (assoc (write :create i i) :value {"$type" "com.example.record" "n" i "text" text})) (range 2)) nil))
    (with-open [conn (db/connection fixture/*ds*)]
      (let [payload (:payload (first (db/query conn "SELECT payload FROM repo_events WHERE did = ? ORDER BY seq DESC LIMIT 1" did)))
            event (codec/decode payload 5000000)]
        (is (> (alength payload) 1048576))
        (is (<= (alength ^bytes (get event "blocks")) 2000000))
        (is (= 2 (count (get event "ops"))))))))
