(ns pds.plc-submission-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.events :as events]
            [pds.handles :as handles]
            [pds.handles-test :refer [request due! changes]]
            [pds.http :as http]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as directory-test]
            [pds.plc-provision-test :as provision-test :refer [rows scalar]]
            [pds.plc-signing :as signing]
            [pds.plc-signing-test :refer [challenge! error stored]]
            [pds.plc-submission :as submission]
            [pds.plc-test :as plc-test]
            [pds.protocol.codec :as codec]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))
(use-fixtures :each fixture/isolated-database)

(defn sign-update [settings account overrides]
  (let [row (stored (:did account))
        signer {:algorithm "ES256K" :private (crypto/unseal (:master-key settings) (str (:did account) ":plc-rotation") (:rotation_key row))}]
    (plc-test/update-op (codec/decode (:operation row)) signer overrides)))

(deftest submit-signed-operation-over-http-and-publish-once
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision-test/settings client origin)
            recovery (plc/did-key (crypto/keypair "ES256"))
            alice (accounts/create! fixture/*ds* settings (assoc (provision-test/signup) "recoveryKey" recovery)) did (:did alice)
            original (codec/decode (:operation (stored did)))
            keys [(last (get original "rotationKeys")) (plc/did-key (crypto/keypair "ES256K"))]
            signed (:operation (signing/sign! fixture/*ds* settings (request alice)
                                              {"token" (challenge! settings alice) "rotationKeys" keys}))
            server (http/start! settings (app/handler settings fixture/*ds*))]
        (try
          (with-open [http (HttpClient/newHttpClient)]
            (let [submit #(api/xrpc http (:port server) "POST" "com.atproto.identity.submitPlcOperation" {"operation" %1} %2)]
              (is (= 401 (:status (submit signed nil))))
              (is (= 200 (:status (submit signed (:accessJwt alice)))))
              (is (= (plc/operation-cid signed) (:operation_cid (stored did))))
              (is (= (plc/operation-cid signed) (:head (directory/audit! client origin did))))
              (is (= 2 (count (changes did))))
              (is (= 200 (:status (submit signed (:accessJwt alice)))))
              (is (= 2 (count (directory-test/posts calls))) "Retry does not resubmit")
              (is (= 2 (count (changes did))) "Retry does not publish another identity event")
              (is (empty? (rows "SELECT * FROM handle_updates")))
              (let [recommended (api/xrpc http (:port server) "GET" "com.atproto.identity.getRecommendedDidCredentials" nil (:accessJwt alice))]
                (is (= keys (get-in recommended [:body "rotationKeys"])) "Do not recommend a removed recovery key"))
              (is (= "IdentityMismatch" (get-in (submit original (:accessJwt alice)) [:body "error"])))))
          (finally ((:stop! server))))))))

(deftest submission-rejects-invalid-credentials-signatures-and-other-identities
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup))
            bob (accounts/create! fixture/*ds* settings (assoc (provision-test/signup) "handle" "bob.example.com" "email" "bob@example.com"))
            original (codec/decode (:operation (stored (:did alice))))
            valid (sign-update settings alice {"alsoKnownAs" ["at://alice.example.com" "https://profile.example.net"]})
            alien (crypto/keypair "ES256K")]
        (doseq [overrides [{"rotationKeys" [(plc/did-key alien)]}
                           {"verificationMethods" {"atproto" (plc/did-key alien)}}
                           {"services" {"atproto_pds" {"type" "Other" "endpoint" (:public-url settings)}}}
                           {"services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" "https://another.example.net"}}}
                           {"alsoKnownAs" ["at://bob.example.com" "at://alice.example.com"]}
                           {"alsoKnownAs" []}]]
          (is (= "InvalidRequest" (error #(submission/submit! fixture/*ds* settings (request alice)
                                                             {"operation" (sign-update settings alice overrides)})))))
        (doseq [operation [nil {} (assoc valid "sig" "bad")
                           (plc/sign-operation (dissoc valid "sig") alien)
                           (plc/sign-operation {"type" "plc_tombstone" "prev" (plc/operation-cid original)} alien)]]
          (is (= "InvalidRequest" (error #(submission/submit! fixture/*ds* settings (request alice) {"operation" operation})))))
        (is (= "InvalidRequest" (error #(submission/submit! fixture/*ds* settings (request bob) {"operation" valid}))))
        (is (= 2 (count (directory-test/posts calls))))
        (is (empty? (rows "SELECT * FROM handle_updates")))
        (is (= 1 (count (changes (:did alice)))))))))

(deftest durable-submission-retries-and-blocks-competing-identity-work
  (directory-test/with-directory
    (fn [{:keys [client origin mode calls]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) did (:did alice)
            first-op (sign-update settings alice {"alsoKnownAs" ["at://alice.example.com" "https://profile.example.net"]})
            competing (sign-update settings alice {"alsoKnownAs" ["at://alice.example.com" "https://different.example.net"]})]
        (reset! mode :ignore)
        (is (= "IdentityUpdatePending" (error #(submission/submit! fixture/*ds* settings (request alice) {"operation" first-op}))))
        (is (= "submit" (:operation_kind (first (rows "SELECT operation_kind FROM handle_updates")))))
        (is (= 1 (count (changes did))))
        (is (= "IdentityUpdatePending" (error #(submission/submit! fixture/*ds* settings (request alice) {"operation" competing}))))
        (is (= "IdentityUpdatePending" (error #(handles/update! fixture/*ds* settings (request alice) {"handle" "alice.example.com"}))))
        (is (= "IdentityUpdatePending" (error #(challenge! settings alice))))
        (db/transact! fixture/*ds* #(accounts/issue-email! % (first (db/query % "SELECT * FROM accounts WHERE did = ?" did)) "delete-account"))
        (is (= "IdentityUpdatePending" (error #(accounts/delete! fixture/*ds* {"did" did "password" "signup-password"
                                                                               "token" (api/email-token "Confirm account deletion")}))))
        (is (= 1 (scalar "SELECT count(*) AS n FROM account_tokens WHERE purpose = 'delete-account'")) "Blocked deletion keeps the proof")
        (due!) (reset! mode :accept)
        (is (= :updated (handles/process-one! fixture/*ds* (assoc settings :plc-url "https://changed.example.net") nil)))
        (is (= (plc/operation-cid first-op) (:operation_cid (stored did))))
        (is (= 3 (count (directory-test/posts calls))))
        (is (= 2 (count (changes did))))))))

(deftest submission-reconciles-local-rollback-after-acceptance
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) did (:did alice)
            op (sign-update settings alice {})]
        (with-redefs [events/append! (fn [& _] (throw (ex-info "Local event storage failed" {})))]
          (is (thrown? Exception (submission/submit! fixture/*ds* settings (request alice) {"operation" op}))))
        (is (= 1 (count (changes did))))
        (is (= 2 (count (directory-test/posts calls))))
        (due!)
        (is (nil? (submission/submit! fixture/*ds* settings (request alice) {"operation" op})))
        (is (= 2 (count (directory-test/posts calls))))
        (is (= 2 (count (changes did))))))))

(deftest submission-reauthenticates-and-rechecks-local-identity-after-network
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) did (:did alice)
            op (sign-update settings alice {}) audit! directory/audit!]
        (with-redefs [directory/audit! (fn [& args]
                                        (db/transact! fixture/*ds* #(db/execute! % "UPDATE sessions SET revoked = true WHERE did = ?" did))
                                        (apply audit! args))]
          (is (= "InvalidToken" (error #(submission/submit! fixture/*ds* settings (request alice) {"operation" op})))))
        (is (= 1 (count (directory-test/posts calls))))
        (is (empty? (rows "SELECT * FROM handle_updates")))))))

(deftest inactive-primary-owner-can-submit-an-already-accepted-operation
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) did (:did alice)
            op (sign-update settings alice {})]
        (directory/ensure-operation! client origin did op)
        (db/transact! fixture/*ds* #(db/execute! % "UPDATE accounts SET status = 'deactivated' WHERE did = ?" did))
        (is (nil? (submission/submit! fixture/*ds* settings (request alice) {"operation" op})))
        (is (= 2 (count (directory-test/posts calls))))
        (is (= 2 (count (changes did))))
        (is (= "deactivated" (:status (first (rows "SELECT status FROM accounts WHERE did = ?" did)))))))))
