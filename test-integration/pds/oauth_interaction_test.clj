(ns pds.oauth-interaction-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app-passwords :as app-passwords]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.oauth.client :as client]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof]
            [pds.oauth.interaction :as interaction]
            [pds.oauth.parameters :as parameters]
            [pds.oauth.par-test :as params]
            [pds.oauth-par-test :as par]
            [pds.server-api-test :as api])
  (:import [java.net URI]))

(use-fixtures :each fixture/isolated-database)
(def did "did:web:alice.example.com")
(def credentials {"identifier" did "password" "correct-password"})
(defn account! [settings]
  (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "correct-password"}))
(defn start
  ([] (start (params/params)))
  ([params]
   (let [pushed (par/push (par/resolver) params (crypto/keypair))
         started (interaction/start! fixture/*ds* metadata/client-id (:request_uri pushed))]
     (merge started (interaction/inspect! fixture/*ds* (:id started) (:browser started))))))
(defn login [settings state body]
  (interaction/authenticate! fixture/*ds* settings (:id state) (:browser state) (:csrf state) body))
(defn decide [settings state approve?]
  (interaction/decide! fixture/*ds* (par/resolver) settings (:id state) (:browser state) (:csrf state) approve?))
(defn fields [result] (parameters/parse! (.getRawQuery (URI/create (:location result)))))
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (or (:error (ex-data e)) (:oauth-error (ex-data e))))))
(defn mutate [sql & args] (db/transact! fixture/*ds* #(apply db/execute! % sql args)))

(deftest independent-browser-secrets-csrf-rotation-and-durable-approval
  (let [settings (api/settings) _ (account! settings) state (start) other (start)]
    (is (= metadata/client-id (:client-id state)))
    (is (nil? (:did state)))
    (doseq [[id browser csrf] [[(:id state) (:browser other) (:csrf state)]
                              [(:id other) (:browser state) (:csrf state)]
                              [(:id state) (:browser state) (:csrf other)]
                              [(:id state) (:browser state) nil]]]
      (is (= "invalid_request" (error #(interaction/authenticate! fixture/*ds* settings id browser csrf credentials)))))
    (is (= "access_denied" (error #(decide settings state true))))
    (let [logged-in (login settings state credentials) authenticated (merge state logged-in)
          result (fields (decide settings authenticated true))
          row (first (par/rows "SELECT *, snapshot::text FROM oauth_codes"))
          snapshot (json/read-str (:snapshot row))]
      (is (= did (:did logged-in)))
      (is (not= (:csrf state) (:csrf logged-in)))
      (is (= (get-in state [:parameters "state"]) (get result "state")))
      (is (= (:public-url settings) (get result "iss")))
      (is (= "login" (get result "source")))
      (is (= (crypto/digest-token (get result "code")) (:code_hash row)))
      (is (= did (:did row)))
      (is (= 0 (:account_epoch row)))
      (is (= 60 (- (.getEpochSecond (.toInstant (:expires_at row))) (.getEpochSecond (.toInstant (:created_at row))))))
      (is (= metadata/client-id (get snapshot "client-id")))
      (is (= (get-in state [:parameters "code_challenge"]) (get-in snapshot ["parameters" "code_challenge"])))
      (is (= 43 (count (get snapshot "dpop-jkt"))))
      (is (= "invalid_request" (error #(decide settings authenticated true))))
      (is (= "invalid_request" (error #(interaction/inspect! fixture/*ds* (:id state) (:browser state)))))
      (is (= 1 (par/scalar "SELECT count(*) AS n FROM sessions"))))
    (let [row (first (par/rows "SELECT browser_hash FROM oauth_interactions WHERE did IS NOT NULL"))]
      (is (= (crypto/digest-token (:browser state)) (:browser_hash row))))))

(deftest only-primary-active-matching-account-can-authenticate
  (let [settings (api/settings) account (account! settings) state (start (assoc (params/params) "login_hint" "ALICE.EXAMPLE.COM"))
        app (db/transact! fixture/*ds* #(app-passwords/create! % settings (assoc account :access-scope "com.atproto.access") {"name" "delegated"}))]
    (doseq [body [(assoc credentials "password" (:password app)) (assoc credentials "password" "wrong")
                  (assoc credentials "identifier" "missing.example.com") (assoc credentials "password" nil)]]
      (is (= "AuthenticationRequired" (error #(login settings state body)))))
    (doseq [status ["deactivated" "taken_down"]]
      (mutate "UPDATE accounts SET status = ? WHERE did = ?" status did)
      (is (= "AuthenticationRequired" (error #(login settings state credentials)))))
    (mutate "UPDATE accounts SET status = 'active' WHERE did = ?" did)
    (let [wrong (start (assoc (params/params) "login_hint" "other.example.com"))]
      (is (= "AuthenticationRequired" (error #(login settings wrong credentials)))))
    (let [authenticated (merge state (login settings state (assoc credentials "identifier" "ALICE@EXAMPLE.COM")))]
      (is (= "invalid_request" (error #(decide settings state true))))
      (is (= "invalid_request" (error #(login settings authenticated credentials))))
      (is (string? (get (fields (decide settings authenticated true)) "code"))))))

(deftest email-factor-commits-challenge-and-consumes-proof-with-authentication
  (let [settings (api/settings) _ (account! settings) state (start)]
    (mutate "UPDATE accounts SET email_confirmed = true, email_auth_factor = true WHERE did = ?" did)
    (is (= {:factor-required true :factor-type :email} (login settings state credentials)))
    (is (= {:factor-required true :factor-type :email} (login settings state credentials)))
    (is (= 1 (par/scalar "SELECT count(*) AS n FROM account_tokens WHERE purpose = 'sign-in'")))
    (is (= "EmailUnavailable" (error #(login (assoc settings :email-enabled false) state credentials))))
    (is (= "InvalidToken" (error #(login settings state (assoc credentials "authFactorToken" "wrong")))))
    (let [token (api/email-token "Sign in to your PDS account")]
      ;; A failed state update must not destroy the one-use factor token.
      (mutate "ALTER TABLE oauth_interactions ADD CONSTRAINT test_auth_failure CHECK (did IS NULL)")
      (is (thrown? java.sql.SQLException (login settings state (assoc credentials "authFactorToken" token))))
      (mutate "ALTER TABLE oauth_interactions DROP CONSTRAINT test_auth_failure")
      (let [authenticated (merge state (login settings state (assoc credentials "authFactorToken" token)))]
        (is (= did (:did authenticated)))
        (is (= "InvalidToken" (error #(login settings (start) (assoc credentials "authFactorToken" token)))))
        (is (string? (get (fields (decide settings authenticated true)) "code")))))))

(deftest credential-and-status-changes-invalidate-consent-even-after-reversal
  (let [settings (api/settings) _ (account! settings)]
    (doseq [[change restore] [["UPDATE accounts SET status = 'deactivated'" "UPDATE accounts SET status = 'active'"]
                              ["UPDATE accounts SET email = 'changed@example.com'" "UPDATE accounts SET email = 'alice@example.com'"]
                              ["UPDATE accounts SET email_auth_factor = true, email_confirmed = true" "UPDATE accounts SET email_auth_factor = false, email_confirmed = false"]
                              ["UPDATE accounts SET email_confirmed = true" "UPDATE accounts SET email_confirmed = false"]]]
      (let [state (start) authenticated (merge state (login settings state credentials))]
        (mutate change) (mutate restore)
        (is (= "access_denied" (error #(decide settings authenticated true))))))
    (let [state (start) authenticated (merge state (login settings state credentials))]
      (accounts/request-reset! fixture/*ds* settings {"email" "alice@example.com"})
      (accounts/reset-password! fixture/*ds* {"password" "replacement-password" "token" (api/email-token "Reset your PDS password")})
      (is (= "access_denied" (error #(decide settings authenticated true)))))
    (is (= 0 (par/scalar "SELECT count(*) AS n FROM oauth_codes")))))

(deftest request-claim-rollback-browser-expiry-and-single-winner-consent
  (let [settings (api/settings) _ (account! settings)]
    (with-redefs [dpop/now (constantly proof/timestamp)]
      (let [pushed (par/push (par/resolver) (params/params) (crypto/keypair)) uri (:request_uri pushed)]
        (mutate "ALTER TABLE oauth_interactions ADD CONSTRAINT test_start_failure CHECK (false)")
        (is (thrown? java.sql.SQLException (interaction/start! fixture/*ds* metadata/client-id uri)))
        (mutate "ALTER TABLE oauth_interactions DROP CONSTRAINT test_start_failure")
        (let [state (interaction/start! fixture/*ds* metadata/client-id uri)]
          (is (= "invalid_request_uri" (error #(interaction/start! fixture/*ds* metadata/client-id uri))))
          (with-redefs [dpop/now (constantly (+ proof/timestamp 599))]
            (is (map? (interaction/inspect! fixture/*ds* (:id state) (:browser state)))))
          (with-redefs [dpop/now (constantly (+ proof/timestamp 600))]
            (is (= "invalid_request" (error #(interaction/inspect! fixture/*ds* (:id state) (:browser state)))))))))
    (let [state (start) authenticated (merge state (login settings state credentials)) gate (promise)
          tasks (mapv (fn [_] (future @gate (try (decide settings authenticated true)
                                               (catch clojure.lang.ExceptionInfo e (:oauth-error (ex-data e)))))) (range 8))]
      (deliver gate true)
      (let [results (mapv #(deref % 15000 :timeout) tasks)]
        (is (= 1 (count (filter map? results))))
        (is (= 7 (count (filter #{"invalid_request"} results)))))
      (is (= 1 (par/scalar "SELECT count(*) AS n FROM oauth_codes"))))))

(deftest denial-client-changes-and-code-insert-rollback
  (let [settings (api/settings) _ (account! settings) state (start)
        denied (fields (decide settings state false))]
    (is (= "access_denied" (get denied "error")))
    (is (= (:public-url settings) (get denied "iss")))
    (is (= (get-in state [:parameters "state"]) (get denied "state")))
    (is (nil? (get denied "code")))
    (is (= "invalid_request" (error #(decide settings state false))))
    (let [state (start) authenticated (merge state (login settings state credentials))
          changed (client/resolver {:oauth-client-fetch (fn [_ _] (metadata/response (assoc (metadata/metadata) "redirect_uris" ["https://app.example.com/changed"])))})]
      (is (= "invalid_request" (error #(interaction/decide! fixture/*ds* changed settings (:id state) (:browser state) (:csrf authenticated) true))))
      (mutate "ALTER TABLE oauth_codes ADD CONSTRAINT test_code_failure CHECK (false)")
      (is (thrown? java.sql.SQLException (decide settings authenticated true)))
      (mutate "ALTER TABLE oauth_codes DROP CONSTRAINT test_code_failure")
      (is (string? (get (fields (decide settings authenticated true)) "code"))))))

(deftest account-version-is-monotonic-and-checked-after-metadata-lookup
  (let [settings (api/settings) _ (account! settings) state (start)
        authenticated (merge state (login settings state credentials))]
    (mutate "UPDATE accounts SET email = email, status = status, handle = handle")
    (is (= 0 (par/scalar "SELECT oauth_epoch AS n FROM accounts")))
    (mutate "UPDATE accounts SET oauth_epoch = oauth_epoch + 1")
    (mutate "UPDATE accounts SET oauth_epoch = 0")
    (is (= 1 (par/scalar "SELECT oauth_epoch AS n FROM accounts")))
    (is (= "access_denied" (error #(decide settings authenticated true))))
    (let [state (start) authenticated (merge state (login settings state credentials))
          changed (client/resolver {:oauth-client-fetch (fn [_ _]
                                                         (mutate "UPDATE accounts SET status = 'taken_down'")
                                                         (metadata/response (metadata/metadata)))})]
      (is (= "access_denied" (error #(interaction/decide! fixture/*ds* changed settings (:id state) (:browser state) (:csrf authenticated) true)))))))

(deftest interaction-expiration-is-rechecked-after-network-io
  (let [settings (api/settings) _ (account! settings) clock (atom proof/timestamp)]
    (with-redefs [dpop/now #(deref clock)]
      (let [state (start) authenticated (merge state (login settings state credentials))
            delayed (client/resolver {:oauth-client-fetch (fn [_ _]
                                                           (swap! clock + interaction/lifetime)
                                                           (metadata/response (metadata/metadata)))})]
        (is (= "invalid_request" (error #(interaction/decide! fixture/*ds* delayed settings (:id state) (:browser state) (:csrf authenticated) true))))
        (is (= 0 (par/scalar "SELECT count(*) AS n FROM oauth_codes")))))))
