(ns pds.service-auth-receive-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.identity :as identity]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as directory-test]
            [pds.plc-provision-test :refer [rows scalar]]
            [pds.plc-test :as plc-test]
            [pds.service-auth :as service-auth]
            [pds.service-auth-test :refer [mint token-request error high-s]])
  (:import [java.time Instant]))
(use-fixtures :each fixture/isolated-database)

(deftest remote-plc-authentication-and-transactional-replay-protection
  (directory-test/with-directory
    (fn [{:keys [client origin]}]
      (let [key (crypto/keypair "ES256K") genesis (plc/sign-operation (plc-test/unsigned [key] key) key)
            issuer (plc/genesis-did genesis) now (auth/now)
            settings {:service-did "did:web:destination.example.com"}
            method "com.atproto.server.createAccount"
            token (service-auth/sign key issuer (str (:service-did settings) "#atproto_pds") method now (+ now 60))
            resolver (identity/resolver {:http-client client :plc-url origin})]
        (directory/ensure-operation! client origin issuer genesis)
        (let [verified (service-auth/verify! resolver settings (token-request token) method)
              alternate (service-auth/verify! resolver settings (token-request (high-s token "ES256K")) method)]
          (is (= issuer (:did verified)))
          (is (= verified alternate))
          (db/transact! fixture/*ds* #(db/execute! % "CREATE TABLE protected_effects (issuer text NOT NULL)"))
          (is (thrown? Exception
                       (db/transact! fixture/*ds*
                         (fn [conn]
                           (service-auth/consume! conn verified)
                           (db/execute! conn "INSERT INTO protected_effects VALUES (?)" issuer)
                           (throw (ex-info "Protected mutation rolled back" {}))))))
          (is (= 0 (scalar "SELECT count(*) AS n FROM service_token_uses")))
          (is (= 0 (scalar "SELECT count(*) AS n FROM protected_effects")))
          (let [tasks (mapv (fn [_] (future
                                      (try
                                        (db/transact! fixture/*ds*
                                          (fn [conn]
                                            (service-auth/consume! conn verified)
                                            (db/execute! conn "INSERT INTO protected_effects VALUES (?)" issuer)))
                                        :used
                                        (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))) (range 12))
                results (mapv #(deref % 15000 :timeout) tasks)]
            (is (= 1 (count (filter #{:used} results))))
            (is (= 11 (count (filter #{"JwtReplay"} results)))))
          (is (= 1 (scalar "SELECT count(*) AS n FROM protected_effects")))
          (is (= [{:issuer issuer :nonce_hash (crypto/digest-token (get-in verified [:claims "jti"]))}]
                 (rows "SELECT issuer, nonce_hash FROM service_token_uses")))
          (is (= "JwtReplay" (error #(db/transact! fixture/*ds* (fn [conn] (service-auth/consume! conn alternate))))))
          (let [reopened (db/datasource {:url (.getURL fixture/*ds*) :user (.getUser fixture/*ds*) :password (.getPassword fixture/*ds*)})]
            (is (= "JwtReplay" (error #(db/transact! reopened (fn [conn] (service-auth/consume! conn verified)))))))
          (with-open [conn (db/connection fixture/*ds*)]
            (is (thrown? Exception (service-auth/consume! conn verified)) "Autocommit consumption cannot be used accidentally")))))))

(deftest replay-ledger-isolates-issuers-and-prunes-expired-entries-in-batches
  (let [now (auth/now) settings {:service-did "did:web:destination.example.com"}
        method "com.atproto.server.createAccount" nonce "shared-test-nonce"
        proofs (mapv (fn [issuer]
                       (let [key (crypto/keypair "ES256")
                             claims {"iss" issuer "aud" (:service-did settings) "lxm" method "iat" now "exp" (+ now 60) "jti" nonce}
                             resolver (identity/resolver {:local-document (fn [_] (pds.service-auth-test/document issuer key))})]
                         (service-auth/verify! resolver settings (token-request (mint key {"typ" "JWT" "alg" "ES256"} claims)) method)))
                     ["did:web:alice.example.com" "did:web:bob.example.com"])]
    (doseq [proof proofs] (db/transact! fixture/*ds* #(service-auth/consume! % proof)))
    (is (= 2 (scalar "SELECT count(*) AS n FROM service_token_uses")))
    (with-redefs [auth/now (constantly (+ now 60))]
      (is (= "JwtExpired" (error #(db/transact! fixture/*ds* (fn [conn] (service-auth/consume! conn (first proofs))))))))
    (is (= 2 (scalar "SELECT count(*) AS n FROM service_token_uses"))))
  (let [now (auth/now)
        key (crypto/keypair "ES256") issuer "did:web:cleanup.example.com"
        method "com.atproto.server.createAccount" settings {:service-did "did:web:destination.example.com"}
        resolver (identity/resolver {:local-document (fn [_] (pds.service-auth-test/document issuer key))})
        proof (service-auth/verify! resolver settings
                 (token-request (service-auth/sign key issuer (:service-did settings) method now (+ now 60))) method)]
    (db/transact! fixture/*ds* #(db/execute! % "INSERT INTO service_token_uses SELECT 'did:web:expired.example.com', n::text, ? FROM generate_series(1, 1005) AS n"
                                            (.minusSeconds (Instant/now) 10)))
    (db/transact! fixture/*ds* #(service-auth/consume! % proof))
    (is (= 5 (scalar "SELECT count(*) AS n FROM service_token_uses WHERE issuer = 'did:web:expired.example.com'")))
    (is (= 3 (scalar "SELECT count(*) AS n FROM service_token_uses WHERE issuer <> 'did:web:expired.example.com'")))))
