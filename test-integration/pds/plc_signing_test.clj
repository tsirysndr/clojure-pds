(ns pds.plc-signing-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.email :as email]
            [pds.handles :as handles]
            [pds.handles-test :refer [request]]
            [pds.http :as http]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as directory-test]
            [pds.plc-provision-test :as provision-test :refer [rows scalar]]
            [pds.plc-signing :as signing]
            [pds.plc-test :as plc-test]
            [pds.protocol.codec :as codec]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)
(def subject "Authorize a PLC identity operation")
(defn challenge! [settings account]
  (signing/request-signature! fixture/*ds* settings (request account))
  (api/email-token subject))
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))
(defn stored [did] (first (rows "SELECT * FROM plc_identities WHERE did = ?" did)))

(deftest authenticated-email-authorized-plc-signing-over-http
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) did (:did alice)
            original (stored did) genesis (codec/decode (:operation original))
            server (http/start! settings (app/handler settings fixture/*ds*))]
        (try
          (with-open [http (HttpClient/newHttpClient)]
            (let [call #(api/xrpc http (:port server) "POST" %1 %2 %3)
                  issue #(call "com.atproto.identity.requestPlcOperationSignature" nil %)
                  sign #(call "com.atproto.identity.signPlcOperation" % (:accessJwt alice))
                  app-password (get-in (call "com.atproto.server.createAppPassword" {"name" "migration-app" "privileged" true} (:accessJwt alice)) [:body "password"])
                  app-access (get-in (call "com.atproto.server.createSession" {"identifier" did "password" app-password} nil) [:body "accessJwt"])]
              (is (= 401 (:status (issue nil))))
              (is (= 403 (:status (issue app-access))))
              (is (= 200 (:status (issue (:accessJwt alice)))))
              (is (= 200 (:status (issue (:accessJwt alice)))))
              (is (= 1 (:n (first (rows "SELECT count(*) AS n FROM email_outbox WHERE payload->>'subject' = ?" subject)))))
              (let [token (api/email-token subject)
                    new-key (plc/did-key (crypto/keypair "ES256"))
                    fields {"rotationKeys" [new-key] "alsoKnownAs" ["at://alice.example.net"]
                            "verificationMethods" {"atproto" new-key}
                            "services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" "https://new-pds.example.net"}}}]
                (is (= 403 (:status (call "com.atproto.identity.signPlcOperation" {"token" token} app-access))))
                (doseq [body [{} {"token" "wrong"} {"token" (api/email-token "Confirm your PDS email")}
                              {"token" token "rotationKeys" []} {"token" token "rotationKeys" ["bad"]}
                              {"token" token "services" []} {"token" token "verificationMethods" nil}
                              {"token" token "alsoKnownAs" [1]} {"token" token "alsoKnownAs" [(apply str (repeat 8000 "x"))]}]]
                  (is (= 400 (:status (sign body)))))
                (let [response (sign (assoc fields "token" token "prev" "ignored" "type" "plc_tombstone"))
                      operation (get-in response [:body "operation"])]
                  (is (= 200 (:status response)))
                  (is (= fields (select-keys operation (keys fields))))
                  (is (= "plc_operation" (get operation "type")))
                  (is (= (plc/operation-cid genesis) (get operation "prev")))
                  (is (= (last (get genesis "rotationKeys")) (plc/signer! (get genesis "rotationKeys") operation)))
                  (is (= (plc/operation-cid operation) (:head (plc/verify-log! did [genesis operation])))))
                (is (= 400 (:status (sign {"token" token}))))
                (is (= (:operation_cid original) (:operation_cid (stored did))))
                (is (= 1 (count (directory-test/posts calls))) "Signing does not submit or mutate the directory")
                (is (= 3 (scalar "SELECT count(*) AS n FROM repo_events")))
                (is (= 0 (scalar "SELECT count(*) AS n FROM account_tokens WHERE purpose = 'plc-operation'"))))))
          (finally ((:stop! server))))))))

(deftest signing-defaults-use-fresh-directory-state-and-allow-inactive-owner
  (directory-test/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) did (:did alice)
            original (stored did) genesis (codec/decode (:operation original))
            signer {:algorithm "ES256K" :private (crypto/unseal (:master-key settings) (str did ":plc-rotation") (:rotation_key original))}
            external (plc-test/update-op genesis signer {"alsoKnownAs" ["at://new.example.net" "https://profile.example.net"]})]
        (directory/ensure-operation! client origin did external)
        (doseq [status ["deactivated" "taken_down"]]
          (db/transact! fixture/*ds* #(db/execute! % "UPDATE accounts SET status = ? WHERE did = ?" status did))
          (let [token (challenge! settings alice)
                operation (:operation (signing/sign! fixture/*ds* settings (request alice) {"token" token}))]
            (is (= (plc/operation-cid external) (get operation "prev")))
            (is (= (dissoc external "sig" "prev") (dissoc operation "sig" "prev")))
            (is (= (last (get genesis "rotationKeys")) (plc/signer! (get external "rotationKeys") operation)))))
        (let [removed (plc-test/update-op external signer {"rotationKeys" [(plc/did-key (crypto/keypair "ES256"))]})
              token (challenge! settings alice)]
          (directory/ensure-operation! client origin did removed)
          (is (= "IdentityMismatch" (error #(signing/sign! fixture/*ds* settings (request alice) {"token" token}))))
          (is (= 1 (scalar "SELECT count(*) AS n FROM account_tokens WHERE purpose = 'plc-operation'"))))))))

(deftest signing-token-expiry-isolation-and-transactional-email
  (directory-test/with-directory
    (fn [{:keys [client origin mode]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup))
            bob (accounts/create! fixture/*ds* settings (assoc (provision-test/signup) "handle" "bob.example.com" "email" "bob@example.com"))
            web (accounts/create! fixture/*ds* (assoc settings :did-method :web) (assoc (provision-test/signup) "handle" "web.example.com" "email" "web@example.com"))]
        (is (= "UnsupportedDID" (error #(challenge! settings web))))
        (is (= "EmailUnavailable" (error #(challenge! (assoc settings :email-enabled false) alice))))
        (with-redefs [email/enqueue! (fn [& _] (throw (ex-info "Outbox insert failure" {})))]
          (is (thrown? Exception (challenge! settings alice))))
        (is (= 0 (scalar "SELECT count(*) AS n FROM account_tokens WHERE purpose = 'plc-operation'")))
        (let [token (challenge! settings alice)]
          (is (= "InvalidToken" (error #(signing/sign! fixture/*ds* settings (request bob) {"token" token}))))
          (with-redefs [directory/audit! (fn [& _] (throw (ex-info "Unavailable" {:type :plc-directory :retryable true})))]
            (is (= "DirectoryUnavailable" (error #(signing/sign! fixture/*ds* settings (request alice) {"token" token})))))
          (is (= 1 (scalar "SELECT count(*) AS n FROM account_tokens WHERE purpose = 'plc-operation'")))
          (db/transact! fixture/*ds* #(db/execute! % "UPDATE account_tokens SET expires_at = now() - interval '1 second' WHERE purpose = 'plc-operation'"))
          (is (= "InvalidToken" (error #(signing/sign! fixture/*ds* settings (request alice) {"token" token})))))
        (reset! mode :ignore)
        (is (= "IdentityUpdatePending" (error #(handles/update! fixture/*ds* settings (request alice) {"handle" "alicia.example.com"}))))
        (is (= "IdentityUpdatePending" (error #(challenge! settings alice))))))))

(deftest only-one-concurrent-signature-can-consume-an-email-token
  (directory-test/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) token (challenge! settings alice)
            entered (promise) release (promise) attempts (atom 0) audit! directory/audit!]
        (with-redefs [directory/audit! (fn [& args]
                                        (let [result (apply audit! args)]
                                          (when (= 2 (swap! attempts inc)) (deliver entered true))
                                          @release result))]
          (let [tasks (mapv (fn [_] (future (try (signing/sign! fixture/*ds* settings (request alice) {"token" token})
                                                (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))) (range 2))]
            (try (is (= true (deref entered 10000 :timeout)))
                 (finally (deliver release true)))
            (let [results (mapv #(deref % 15000 :timeout) tasks)]
              (is (= 1 (count (filter map? results))))
              (is (= 1 (count (filter #{"InvalidToken"} results)))))))))))

(deftest signing-reauthenticates-after-directory-lookup
  (directory-test/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) token (challenge! settings alice)
            audit! directory/audit!]
        (with-redefs [directory/audit! (fn [& args]
                                        (db/transact! fixture/*ds* #(db/execute! % "UPDATE sessions SET revoked = true WHERE did = ?" (:did alice)))
                                        (apply audit! args))]
          (is (= "InvalidToken" (error #(signing/sign! fixture/*ds* settings (request alice) {"token" token}))))
          (is (= 1 (scalar "SELECT count(*) AS n FROM account_tokens WHERE purpose = 'plc-operation'"))))))))
