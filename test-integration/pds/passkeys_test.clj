(ns pds.passkeys-test
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.oauth-interaction-test :as browser]
            [pds.oauth-par-test :as rows]
            [pds.security.passkeys :as passkeys]
            [pds.server-api-test :as api]))

(use-fixtures :each fixture/isolated-database)
(defn tx [f] (db/transact! fixture/*ds* f))
(defn authenticator [options & {:as args}]
  (let [result (shell/sh "node" "scripts/conformance/webauthn-fixture.mjs" :in (json/write-str (assoc args :options options)))]
    (when-not (zero? (:exit result)) (throw (ex-info (:err result) {})))
    (json/read-str (:out result))))
(defn start-registration [settings browser]
  (tx #(passkeys/begin-registration! % settings browser/did browser "My device")))
(defn start-authentication [settings browser]
  (tx #(passkeys/begin-authentication! % settings browser/did browser)))
(defn finish-registration [settings state browser response]
  (tx #(passkeys/finish-registration! % settings (:id state) browser (json/write-str response))))
(defn finish-authentication [settings state browser response]
  (tx #(passkeys/finish-authentication! % settings (:id state) browser (json/write-str response))))
(defn register [settings browser & {:as args}]
  (let [started (start-registration settings browser) fixture (apply authenticator (:options started) (mapcat identity args))
        result (finish-registration settings started browser (get fixture "response"))]
    (is (string? (:credential-id result)) (str result))
    (get fixture "credential")))

(deftest passkey-algorithms-registration-authentication-and-storage
  (let [settings (api/settings) _ (browser/account! settings) browser (crypto/token)]
    (doseq [algorithm ["ES256" "EdDSA" "RS256"]]
      (let [credential (register settings browser :algorithm algorithm)
            state (start-authentication settings browser)
            response (get (authenticator (:options state) :mode "authenticate" :credential credential) "response")
            result (finish-authentication settings state browser response)]
        (is (= browser/did (:did result)))
        (is (= :passkey (:authentication result)))
        (is (= (get credential "id") (:credential-id result)))
        (is (= "InvalidPasskey" (browser/error #(finish-authentication settings state browser response))))))
    (let [credentials (tx #(passkeys/list-credentials % browser/did))]
      (is (= 3 (count credentials)))
      (is (every? :last-used-at credentials))
      (is (every? #(= "My device" (:name %)) credentials))
      (is (= 3 (rows/scalar "SELECT oauth_epoch AS n FROM accounts"))))
    (is (= 0 (rows/scalar "SELECT count(*) AS n FROM sessions WHERE NOT revoked")))
    (is (= 1 (rows/scalar "SELECT count(*) AS n FROM account_webauthn_users")))
    (is (= 3 (rows/scalar "SELECT count(*) AS n FROM account_passkeys WHERE signature_count = 1")))))

(deftest registration-options-and-browser-binding
  (let [settings (api/settings) _ (browser/account! settings) browser (crypto/token)
        started (start-registration settings browser) options (:options started)
        fixture (authenticator options)]
    (is (= "pds.example.com" (get-in options ["publicKey" "rp" "id"])))
    (is (= "required" (get-in options ["publicKey" "authenticatorSelection" "residentKey"])))
    (is (= "required" (get-in options ["publicKey" "authenticatorSelection" "userVerification"])))
    (is (= "none" (get-in options ["publicKey" "attestation"])))
    (is (= 300 (:expires-in started)))
    (is (not= browser/did (get-in options ["publicKey" "user" "id"])))
    (is (= "InvalidPasskey" (browser/error #(finish-registration settings started (crypto/token) (get fixture "response")))))
    (is (= "InvalidPasskey" (browser/error #(finish-authentication settings started browser (get fixture "response")))))
    (is (string? (:credential-id (finish-registration settings started browser (get fixture "response")))))
    (is (= "InvalidPasskey" (browser/error #(finish-registration settings started browser (get fixture "response")))))
    (let [again (start-registration settings browser)]
      (is (= 1 (count (get-in again [:options "publicKey" "excludeCredentials"])))))))

(deftest invalid-registration-responses-consume-the-ceremony
  (let [settings (api/settings) _ (browser/account! settings) browser (crypto/token)]
    (doseq [args [{:origin "https://evil.example.com"} {:origin "https://sub.pds.example.com"}
                 {:origin "https://pds.example.com:8443"} {:rpId "evil.example.com"}
                 {:flags 0x41} {:flags 0x44} {:clientData {"crossOrigin" true}}
                 {:clientData {"topOrigin" "https://evil.example.com"}} {:clientData {"challenge" (crypto/token)}}
                 {:clientData {"type" "webauthn.get"}}]]
      (let [started (start-registration settings browser)
            response (get (apply authenticator (:options started) (mapcat identity args)) "response")]
        (is (= "InvalidPasskey" (:error (finish-registration settings started browser response))) (str args))
        (is (= "InvalidPasskey" (browser/error #(finish-registration settings started browser response))))))
    (is (= 0 (rows/scalar "SELECT count(*) AS n FROM account_passkeys")))))

(deftest invalid-signatures-ownership-flags-and-counter-replay
  (let [settings (api/settings) _ (browser/account! settings) browser (crypto/token)
        credential (register settings browser)]
    (doseq [args [{:origin "https://evil.example.com"} {:rpId "evil.example.com"} {:flags 1} {:flags 4}
                 {:corruptSignature true} {:clientData {"crossOrigin" true}} {:clientData {"type" "webauthn.create"}}
                 {:clientData {"challenge" (crypto/token)}} {:flags 0x1d}]]
      (let [started (start-authentication settings browser)
            response (get (apply authenticator (:options started) :mode "authenticate" :credential credential (mapcat identity args)) "response")]
        (is (= "InvalidPasskey" (:error (finish-authentication settings started browser response))) (str args))))
    (let [first (start-authentication settings browser) second (start-authentication settings browser)
          response (fn [state] (get (authenticator (:options state) :mode "authenticate" :credential credential :counter 7) "response"))]
      (is (= browser/did (:did (finish-authentication settings first browser (response first)))))
      (is (= "InvalidPasskey" (:error (finish-authentication settings second browser (response second))))))
    (let [started (start-authentication settings browser)
          response (-> (authenticator (:options started) :mode "authenticate" :credential credential :counter 8)
                       (get "response") (assoc-in ["response" "userHandle"] (crypto/token)))]
      (is (= "InvalidPasskey" (:error (finish-authentication settings started browser response)))))))

(deftest expiry-security-version-and-removal-invalidate-pending-ceremonies
  (let [settings (api/settings) _ (browser/account! settings) browser (crypto/token)
        credential (register settings browser) started (start-authentication settings browser)
        response (get (authenticator (:options started) :mode "authenticate" :credential credential) "response")]
    (browser/mutate "UPDATE accounts SET status = 'deactivated'")
    (is (= "InvalidPasskey" (browser/error #(finish-authentication settings started browser response))))
    (browser/mutate "UPDATE accounts SET status = 'active'")
    (is (= "InvalidPasskey" (browser/error #(finish-authentication settings started browser response))))
    (let [started (start-authentication settings browser)
          response (get (authenticator (:options started) :mode "authenticate" :credential credential) "response")]

      (with-redefs [passkeys/now (constantly (+ 301 (passkeys/now)))]
        (is (= "InvalidPasskey" (browser/error #(finish-authentication settings started browser response))))))
    (let [started (start-authentication settings browser)
          response (get (authenticator (:options started) :mode "authenticate" :credential credential) "response")]
      (is (= {:removed true} (tx #(passkeys/remove! % browser/did (get credential "id")))))
      (is (= "InvalidPasskey" (browser/error #(finish-authentication settings started browser response))))
      (is (= [] (tx #(passkeys/list-credentials % browser/did)))))))

(deftest concurrent-finish-and-rollback
  (let [settings (api/settings) _ (browser/account! settings) browser (crypto/token)
        credential (register settings browser) started (start-authentication settings browser)
        response (get (authenticator (:options started) :mode "authenticate" :credential credential) "response")]
    (is (thrown? Exception
          (tx (fn [conn]
                (is (= browser/did (:did (passkeys/finish-authentication! conn settings (:id started) browser (json/write-str response)))))
                (throw (ex-info "Failed session insert" {}))))))
    (let [gate (promise) tasks (mapv (fn [_] (future @gate
                                            (try (finish-authentication settings started browser response)
                                                 (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))) (range 8))]
      (deliver gate true)
      (let [results (mapv #(deref % 15000 :timeout) tasks)]
        (is (= 1 (count (filter map? results))))
        (is (= 7 (count (filter #{"InvalidPasskey"} results))))))))

(deftest synced-passkeys-zero-counters-and-backup-state
  (let [settings (api/settings) _ (browser/account! settings) browser (crypto/token)
        credential (register settings browser :flags 0x4d)]
    (doseq [flags [0x0d 0x1d 0x0d]]
      (let [started (start-authentication settings browser)
            response (get (authenticator (:options started) :mode "authenticate" :credential credential :flags flags :counter 0) "response")]
        (is (= browser/did (:did (finish-authentication settings started browser response))))
        (is (= (pos? (bit-and flags 0x10)) (:backed_up (first (rows/rows "SELECT backed_up FROM account_passkeys")))))))
    (let [started (start-authentication settings browser)
          response (get (authenticator (:options started) :mode "authenticate" :credential credential :flags 0x05) "response")]
      (is (= "InvalidPasskey" (:error (finish-authentication settings started browser response)))))))

(deftest malformed-registration-inputs-are-bounded-and-consumed
  (let [settings (api/settings) _ (browser/account! settings) browser (crypto/token)]
    (doseq [mutate [(fn [_] {}) #(assoc % "rawId" (crypto/token))
                    #(assoc-in % ["clientExtensionResults" "credProps" "rk"] false)
                    #(assoc-in % ["response" "attestationObject"] "")
                    #(assoc-in % ["response" "attestationObject"] "AA")
                    #(assoc-in % ["response" "clientDataJSON"] "bad-base64!")
                    #(assoc-in % ["response" "clientDataJSON"] (crypto/b64 (.getBytes "[]" "UTF-8")))]]
      (let [started (start-registration settings browser) response (mutate (get (authenticator (:options started)) "response"))]
        (is (= "InvalidPasskey" (:error (finish-registration settings started browser response))))))
    (let [started (start-registration settings browser)]
      (is (= "InvalidPasskey" (:error (tx #(passkeys/finish-registration! % settings (:id started) browser (apply str (repeat 65537 "x"))))))))
    (is (= 0 (rows/scalar "SELECT count(*) AS n FROM account_passkeys")))))

(deftest ownership-and-deletion
  (let [settings (api/settings) _ (browser/account! settings) browser (crypto/token)
        credential (register settings browser)
        bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "bob-password"})
        started (tx #(passkeys/begin-authentication! % settings (:did bob) browser))
        response (get (authenticator (:options started) :mode "authenticate" :credential credential) "response")]
    (is (= "InvalidPasskey" (:error (finish-authentication settings started browser response))))
    (is (= "PasskeyNotFound" (browser/error #(tx (fn [conn] (passkeys/remove! conn (:did bob) (get credential "id")))))))
    (tx #(accounts/issue-email! % {:did browser/did :email "alice@example.com"} "delete-account"))
    (accounts/delete! fixture/*ds* {"did" browser/did "password" "correct-password" "token" (api/email-token "Confirm account deletion")})
    (is (= 0 (rows/scalar "SELECT count(*) AS n FROM account_passkeys")))
    (is (= 0 (rows/scalar "SELECT count(*) AS n FROM account_webauthn_users")))
    (is (= 0 (rows/scalar "SELECT count(*) AS n FROM webauthn_challenges WHERE did = 'did:web:alice.example.com'")))))

(deftest challenge-cap-and-expired-cleanup
  (let [settings (api/settings) _ (browser/account! settings) browser (crypto/token) clock (passkeys/now)]
    (with-redefs [passkeys/now (constantly clock)]
      (dotimes [_ 8] (start-authentication settings browser))
      (is (= "TooManyChallenges" (browser/error #(start-authentication settings browser)))))
    (with-redefs [passkeys/now (constantly (+ clock 300))]
      (is (map? (start-authentication settings browser)))
      (is (= 1 (rows/scalar "SELECT count(*) AS n FROM webauthn_challenges"))))))
