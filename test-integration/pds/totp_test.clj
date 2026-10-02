(ns pds.totp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.http :as http]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.oauth-interaction-test :as browser]
            [pds.oauth-par-test :as rows]
            [pds.security.factors :as factors]
            [pds.security.totp :as totp]
            [pds.server-api-test :as api])
  (:import [java.time Instant]
           [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)
(defn tx [f] (db/transact! fixture/*ds* f))
(defn factor-row [] (first (rows/rows "SELECT * FROM account_totp")))
(defn secret [settings] (crypto/unseal (:master-key settings) (str "pds/totp/v1/" browser/did) (:sealed_secret (factor-row))))
(defn current-code [settings] (totp/code (secret settings) (quot (factors/now) 30)))
(defn enroll [settings]
  (tx #(factors/begin! % settings browser/did))
  (tx #(factors/confirm! % settings browser/did (current-code settings))))

(deftest optional-enrollment-encryption-activation-and-invalidation
  (let [settings (api/settings) account (browser/account! settings)
        started (tx #(factors/begin! % settings browser/did))
        row (factor-row)]
    (is (= 600 (:expires-in started)))
    (is (re-find #"^otpauth://totp/pds.example.com:alice.example.com" (:uri started)))
    (is (not= (vec (secret settings)) (vec (:sealed_secret row))))
    (is (false? (tx #(factors/enabled? % browser/did))))
    (is (string? (:accessJwt (accounts/login! fixture/*ds* settings browser/credentials))))
    (is (= "InvalidToken" (:error (tx #(factors/confirm! % settings browser/did "bad")))))
    (let [confirmed (tx #(factors/confirm! % settings browser/did (current-code settings)))
          codes (:recovery-codes confirmed)]
      (is (= 10 (count (set codes))))
      (is (true? (tx #(factors/enabled? % browser/did))))
      ;; Turning the factor on keeps the owner signed in: the session that did
      ;; it just proved the password and the new secret, matching the other
      ;; implementations behind the shared console.
      (is (= 0 (rows/scalar "SELECT oauth_epoch AS n FROM accounts")))
      (is (= 2 (rows/scalar "SELECT count(*) AS n FROM sessions WHERE NOT revoked")))
      (is (map? (tx (fn [conn] (auth/authenticate! conn settings {:headers {"authorization" (str "Bearer " (:accessJwt account))}})))))
      (is (= "TotpAlreadyEnabled" (browser/error #(tx (fn [conn] (factors/begin! conn settings browser/did))))))
      (is (= "InvalidEnrollment" (browser/error #(tx (fn [conn] (factors/confirm! conn settings browser/did (current-code settings)))))))
      (is (= 10 (rows/scalar "SELECT count(*) AS n FROM account_recovery_codes")))
      (is (not-any? (set codes) (map :code_hash (rows/rows "SELECT code_hash FROM account_recovery_codes"))))
      (is (= "InvalidToken" (:error (tx #(factors/verify! % settings browser/did (current-code settings))))))
      (is (= {:valid? true} (tx #(factors/verify! % settings browser/did (first codes)))))
      (is (= "InvalidToken" (:error (tx #(factors/verify! % settings browser/did (first codes))))))
      (is (= 9 (rows/scalar "SELECT count(*) AS n FROM account_recovery_codes"))))))

(deftest concurrent-replay-and-persistent-attempt-budget
  (let [settings (api/settings) _ (browser/account! settings) _ (enroll settings)
        clock (+ (factors/now) 30)]
    (with-redefs [factors/now (constantly clock)]
      (let [code (current-code settings) gate (promise)
            tasks (mapv (fn [_] (future @gate (tx #(factors/verify! % settings browser/did code)))) (range 6))]
        (deliver gate true)
        (let [results (mapv #(deref % 10000 :timeout) tasks)]
          (is (= 1 (count (filter :valid? results))))
          (is (= 5 (count (filter #(= "InvalidToken" (:error %)) results))))))
      (is (= 5 (:failed_attempts (factor-row))))
      (is (= "RateLimitExceeded" (:error (tx #(factors/verify! % settings browser/did (current-code settings)))))))
    (with-redefs [factors/now (constantly (+ clock 301))]
      (is (= {:valid? true} (tx #(factors/verify! % settings browser/did (current-code settings)))))
      (is (= 0 (:failed_attempts (factor-row)))))))

(deftest pending-enrollment-expiry-security-version-and-email-exclusion
  (let [settings (api/settings) _ (browser/account! settings)]
    (tx #(factors/begin! % settings browser/did))
    (browser/mutate "UPDATE accounts SET password_hash = ?" (crypto/password-hash "replacement-password"))
    (is (= "InvalidEnrollment" (browser/error #(tx (fn [conn] (factors/confirm! conn settings browser/did (current-code settings)))))))
    (tx #(factors/begin! % settings browser/did))
    (browser/mutate "UPDATE account_totp SET enrollment_expires_at = ?" (Instant/ofEpochSecond (factors/now)))
    (is (= "InvalidEnrollment" (browser/error #(tx (fn [conn] (factors/confirm! conn settings browser/did (current-code settings)))))))
    (browser/mutate "UPDATE accounts SET email_confirmed = true, email_auth_factor = true")
    (is (= "EmailFactorEnabled" (browser/error #(tx (fn [conn] (factors/begin! conn settings browser/did))))))
    (is (false? (tx #(factors/enabled? % browser/did))))))

(deftest login-requires-factor-and-failed-attempts-commit-in-both-flows
  (let [settings (api/settings) _ (browser/account! settings) confirmed (enroll settings)
        recovery (first (:recovery-codes confirmed)) state (browser/start)]
    (is (= "AuthFactorTokenRequired" (browser/error #(accounts/login! fixture/*ds* settings browser/credentials))))
    (is (= {:factor-required true :factor-type :totp} (browser/login settings state browser/credentials)))
    (is (= "InvalidToken" (browser/error #(accounts/login! fixture/*ds* settings (assoc browser/credentials "authFactorToken" "wrong")))))
    (is (= "InvalidToken" (:error (browser/login settings state (assoc browser/credentials "authFactorToken" "wrong")))))
    (is (= 2 (:failed_attempts (factor-row))))
    (let [session (accounts/login! fixture/*ds* settings (assoc browser/credentials "authFactorToken" recovery))]
      (is (= browser/did (:did session)))
      (is (= 0 (:failed_attempts (factor-row))))
      (is (= "InvalidToken" (:error (browser/login settings state (assoc browser/credentials "authFactorToken" recovery))))))
    (let [login (browser/login settings state (assoc browser/credentials "authFactorToken" (second (:recovery-codes confirmed))))]
      (is (= browser/did (:did login)))
      (is (string? (get (browser/fields (browser/decide settings (merge state login) true)) "code"))))
    (dotimes [_ 5]
      (is (= "InvalidToken" (browser/error #(accounts/login! fixture/*ds* settings (assoc browser/credentials "authFactorToken" "wrong"))))))
    (is (= "RateLimitExceeded" (browser/error #(accounts/login! fixture/*ds* settings (assoc browser/credentials "authFactorToken" "wrong")))))
    (let [second (browser/start)]
      (is (= "RateLimitExceeded" (:error (browser/login settings second (assoc browser/credentials "authFactorToken" "wrong"))))))))

(deftest recovery-removal-and-business-rollback
  (let [settings (api/settings) _ (browser/account! settings) codes (:recovery-codes (enroll settings))]
    (is (thrown? Exception
          (tx (fn [conn] (is (= {:valid? true} (factors/verify! conn settings browser/did (first codes))))
                (throw (ex-info "Business transaction failed" {}))))))
    (is (= 10 (rows/scalar "SELECT count(*) AS n FROM account_recovery_codes")))
    (is (= "InvalidToken" (:error (tx #(factors/disable! % settings browser/did "wrong")))))
    (is (= {:disabled true} (tx #(factors/disable! % settings browser/did (first codes)))))
    (is (= 0 (rows/scalar "SELECT count(*) AS n FROM account_recovery_codes")))
    (is (false? (tx #(factors/enabled? % browser/did))))
    (is (= 0 (rows/scalar "SELECT oauth_epoch AS n FROM accounts")))
    (is (string? (:accessJwt (accounts/login! fixture/*ds* settings browser/credentials))))))

(deftest factor-survives-password-reset-and-is-erased-on-deletion
  (let [settings (api/settings) _ (browser/account! settings) codes (:recovery-codes (enroll settings))]
    (accounts/request-reset! fixture/*ds* settings {"email" "alice@example.com"})
    ;; A reset begins signed out, so the mail links to the page that takes a new
    ;; password, with the token in the path.
    (let [token (api/email-token "Reset your PDS password")
          text (:text (api/last-email "Reset your PDS password"))]
      (is (str/includes? text (str (:public-url settings) "/account/reset/" token)))
      (accounts/reset-password! fixture/*ds* {"password" "replacement-password" "token" token}))
    (let [credentials (assoc browser/credentials "password" "replacement-password")]
      (is (= "AuthFactorTokenRequired" (browser/error #(accounts/login! fixture/*ds* settings credentials))))
      (let [session (accounts/login! fixture/*ds* settings (assoc credentials "authFactorToken" (first codes)))]
        (tx (fn [conn]
              (let [account (auth/authenticate! conn settings {:headers {"authorization" (str "Bearer " (:accessJwt session))}})]
                (accounts/request-deletion! conn settings account))))))
    (accounts/delete! fixture/*ds* {"did" browser/did "password" "replacement-password" "token" (api/email-token "Confirm account deletion")})
    (is (= 0 (rows/scalar "SELECT count(*) AS n FROM account_totp")))
    (is (= 0 (rows/scalar "SELECT count(*) AS n FROM account_recovery_codes")))))

(deftest legacy-http-login-enforces-enabled-authenticator
  (let [settings (api/settings) _ (browser/account! settings) codes (:recovery-codes (enroll settings))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [login #(api/xrpc client (:port server) "POST" "com.atproto.server.createSession" % nil)]
          (is (= "AuthFactorTokenRequired" (get-in (login browser/credentials) [:body "error"])))
          (is (= "InvalidToken" (get-in (login (assoc browser/credentials "authFactorToken" "wrong")) [:body "error"])))
          (is (= browser/did (get-in (login (assoc browser/credentials "authFactorToken" (first codes))) [:body "did"])))
          (is (= "InvalidToken" (get-in (login (assoc browser/credentials "authFactorToken" (first codes))) [:body "error"])))))
      (finally ((:stop! server))))))
