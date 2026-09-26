(ns pds.repo-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.repo :as repo]))
(use-fixtures :each fixture/isolated-database)
(def settings {:master-key (crypto/random-bytes 32)})
(def did "did:web:alice.example.com")
(def collection "app.bsky.feed.post")
(defn initialize! []
  (db/transact! fixture/*ds*
    (fn [c]
      (db/execute! c "INSERT INTO accounts(did, handle, email, password_hash) VALUES (?, ?, ?, ?)"
                   did "alice.example.com" "alice@example.com" "unused")
      (repo/initialize! c settings did))))
(defn write! [writes swap]
  (db/transact! fixture/*ds* #(repo/apply-writes! % settings did writes swap)))
(defn create-write [rkey] {:action :create :collection collection :rkey rkey
                           :value {"$type" collection "text" "Hello" "createdAt" "2026-09-26T00:00:00Z"}})
(deftest signed-repo-roundtrip-and-swaps
  (let [initial (initialize!)
        result (write! [(create-write "one") (create-write "two")] (:cid initial))
        id (:cid (first (:results result)))]
    (is (= 2 (count (:results result))))
    (is (neg? (compare (:rev initial) (get-in result [:commit :rev]))))
    (is (thrown? Exception (write! [(create-write "three")] (:cid initial))))
    (is (thrown? Exception (write! [(assoc (create-write "one") :action :put :swap-record? true :swap-record "bad")] nil)))
    (db/transact! fixture/*ds*
      (fn [c]
        (let [exported (car/decode (repo/export-car c did))
              head (first (:roots exported))
              commit (codec/decode (get-in exported [:blocks head]))
              public (:public_key (repo/state c did))]
          (is (= (get-in result [:commit :cid]) head))
          (is (crypto/verify "ES256" public (codec/encode (dissoc commit "sig")) (get commit "sig")))
          (is (contains? (:blocks exported) (:cid (get commit "data"))))
          (is (contains? (:blocks exported) id))
          (is (= "Hello" (get-in (repo/record c did collection "one") [:value "text"]))))))
    ;; A failure in the second operation rolls the first operation back too.
    (is (thrown? Exception (write! [(create-write "three") (create-write "one")] nil)))
    (with-open [c (db/connection fixture/*ds*)]
      (is (nil? (repo/record c did collection "three"))))
    (write! [{:action :delete :collection collection :rkey "one" :swap-record? true :swap-record id}] nil)
    (with-open [c (db/connection fixture/*ds*)]
      (is (nil? (repo/record c did collection "one")))
      (is (= id (:cid (repo/record c did collection "two")))))))

(deftest concurrent-swap-has-one-winner
  (let [head (:cid (initialize!))
        gate (promise)
        jobs (mapv (fn [key]
                     (future @gate
                             (try (write! [(create-write key)] head) :committed
                                  (catch clojure.lang.ExceptionInfo e
                                    (if (= "InvalidSwap" (:error (ex-data e))) :conflict (throw e))))))
                   ["one" "two"])]
    (deliver gate true)
    (is (= {:committed 1 :conflict 1} (frequencies (mapv #(deref % 10000 :timeout) jobs))))))
