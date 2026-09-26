(ns pds.auth-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]))
(use-fixtures :each fixture/isolated-database)
(def settings (merge {:service-did "did:web:pds.example.com"}
                     (auth/settings {"PDS_MASTER_KEY" (crypto/b64 (crypto/random-bytes 32))})))
(defn request [token] {:headers {"authorization" (str "Bearer " token)}})
(defn session! []
  (db/transact! fixture/*ds*
    (fn [c]
      (db/execute! c "INSERT INTO accounts(did, handle, email, password_hash) VALUES (?, ?, ?, ?)"
                   "did:web:alice.example.com" "alice.example.com" "alice@example.com" "unused")
      (auth/issue! c settings "did:web:alice.example.com" nil))))
(defn authenticate [token]
  (with-open [c (db/connection fixture/*ds*)] (auth/authenticate! c settings (request token))))
(deftest session-rotation-and-replay
  (let [initial (session!) rotated (auth/refresh! fixture/*ds* settings (request (:refreshJwt initial)))]
    (is (= "alice.example.com" (:handle (authenticate (:accessJwt rotated)))))
    (is (thrown? Exception (authenticate (:refreshJwt rotated))))
    (is (thrown? Exception (auth/refresh! fixture/*ds* settings (request (:accessJwt initial)))))
    (is (thrown? Exception (auth/refresh! fixture/*ds* settings (request (:refreshJwt initial)))))
    (is (thrown? Exception (authenticate (:accessJwt rotated))))
    (is (thrown? Exception (auth/refresh! fixture/*ds* settings (request (:refreshJwt rotated)))))))
(deftest token-expiration-signature-and-revocation
  (let [session (session!) token (:accessJwt session)]
    (is (thrown? Exception (authenticate (str token "tampered"))))
    (with-redefs [auth/now #(+ 901 (.getEpochSecond (java.time.Instant/now))) ]
      (is (thrown? Exception (authenticate token))))
    (auth/delete-session! fixture/*ds* settings (request (:refreshJwt session)))
    (is (thrown? Exception (authenticate token)))))
