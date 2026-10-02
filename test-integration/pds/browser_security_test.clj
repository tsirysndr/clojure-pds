(ns pds.browser-security-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-par-test :as rows]
            [pds.passkeys-test :as authenticator]
            [pds.security.browser :as browser]
            [pds.security.web :as web]
            [pds.server-api-test :as api]
            [pds.totp-test :as totp]))

(use-fixtures :each fixture/isolated-database)
(defn query [sql & args]
  (with-open [conn (db/connection fixture/*ds*)] (apply db/query conn sql args)))
(defn open [] (browser/open! fixture/*ds* nil))
(defn act [settings state action body]
  (browser/action! fixture/*ds* settings (:token state) (get-in state [:view :csrf]) action body))
(defn login [settings] (act settings (open) "login/password" owner/credentials))

(deftest sessions-rotate-csrf-expire-and-revoke
  (let [settings (api/settings) _ (owner/account! settings) anonymous (open)
        other (open) authenticated (act settings anonymous "login/password" owner/credentials)]
    (is (= "login" (get-in anonymous [:view :stage])))
    (is (= "authenticated" (get-in authenticated [:view :stage])))
    (is (not= (:token anonymous) (:token authenticated)))
    (is (not= (get-in anonymous [:view :csrf]) (get-in authenticated [:view :csrf])))
    (is (= "BrowserSessionRequired" (owner/error #(act settings anonymous "totp/begin" {}))))
    (is (= "InvalidCsrf" (owner/error #(browser/action! fixture/*ds* settings (:token authenticated) (get-in other [:view :csrf]) "totp/begin" {}))))
    (is (= "BrowserSessionRequired" (owner/error #(act settings other "totp/begin" {}))))
    (is (not-any? #{(:token authenticated)} (map :token_hash (query "SELECT * FROM browser_sessions"))))
    (is (= (:view authenticated) (:view (browser/open! fixture/*ds* (:token authenticated)))))
    (with-redefs [browser/now (constantly (+ 301 (browser/now)))]
      (is (= "BrowserSessionRequired" (owner/error #(act settings authenticated "totp/begin" {}))))
      (is (= "login" (get-in (browser/open! fixture/*ds* (:token authenticated)) [:view :stage]))))
    (owner/mutate "UPDATE accounts SET password_hash = 'changed'")
    (is (= "BrowserSessionRequired" (owner/error #(act settings authenticated "totp/begin" {}))))))

(deftest totp-enrollment-keeps-every-session-signed-in
  (let [settings (api/settings) _ (owner/account! settings) authenticated (login settings) other (login settings)
        expires (:expires_at (first (query "SELECT * FROM browser_sessions WHERE token_hash = ?" (crypto/digest-token (:token authenticated)))))
        started (act settings authenticated "totp/begin" {})
        confirmed (act settings started "totp/confirm" {"code" (totp/current-code settings)})
        recovery (get-in confirmed [:result :recovery-codes])]
    (is (= "authenticated" (get-in confirmed [:view :stage])))
    (is (= "totp" (get-in confirmed [:view :factor])))
    (is (= 10 (count recovery)))
    ;; Turning the factor on keeps the owner signed in, here and in every other
    ;; browser: the other implementations behind the shared console do the same.
    (is (= (:token started) (:token confirmed)))
    (is (= expires (:expires_at (first (query "SELECT * FROM browser_sessions WHERE token_hash = ?" (crypto/digest-token (:token confirmed)))))))
    (is (some? (get-in (act settings other "passkeys/begin" {"name" "Other browser"}) [:result :id])))
    (let [pending (login settings)]
      (is (= "factor" (get-in pending [:view :stage])))
      (is (nil? (get-in pending [:view :passkeys])))
      (is (= "BrowserSessionRequired" (owner/error #(act settings pending "totp/disable" {"code" (first recovery)}))))
      (doseq [_ (range 5)]
        (is (= "InvalidToken" (get-in (act settings pending "login/factor" {"code" "invalid"}) [:result :error]))))
      (is (= 5 (:failed_attempts (totp/factor-row))))
      (is (= "RateLimitExceeded" (get-in (act settings pending "login/factor" {"code" (first recovery)}) [:result :error])))
      (owner/mutate "UPDATE account_totp SET failed_attempts = 0")
      (let [verified (act settings pending "login/factor" {"code" (first recovery)})]
        (is (= "authenticated" (get-in verified [:view :stage])))
        (is (not= (:token verified) (:token pending)))
        (is (= 9 (rows/scalar "SELECT count(*) AS n FROM account_recovery_codes")))
        (is (= {:logout true} (act settings verified "logout" {})))
        (is (= "BrowserSessionRequired" (owner/error #(act settings verified "totp/begin" {}))))))))

(deftest browser-passkey-registration-login-second-factor-and-removal
  (let [settings (api/settings) _ (owner/account! settings) authenticated (login settings)
        started (act settings authenticated "passkeys/begin" {"name" "Laptop"})
        fixture (authenticator/authenticator (get-in started [:result :options]))
        payload {"id" (get-in started [:result :id]) "response" (json/write-str (get fixture "response"))}
        added (act settings started "passkeys/finish" payload)
        credential (get fixture "credential")]
    (is (= "Laptop" (get-in added [:view :passkeys 0 :name])))
    ;; Adding a passkey keeps the owner signed in: the browser session that just
    ;; proved the password is not rotated away, matching the other
    ;; implementations behind the shared console. Login-stage transitions still
    ;; rotate tokens; this is not one.
    (is (= (:token authenticated) (:token added)))
    (let [begin (act settings (open) "login/passkey/begin" {"identifier" owner/did})
          response (authenticator/authenticator (get-in begin [:result :options]) :mode "authenticate" :credential credential)
          signed-in (act settings begin "login/passkey/finish" {"id" (get-in begin [:result :id]) "response" (json/write-str (get response "response"))})]
      (is (= "authenticated" (get-in signed-in [:view :stage])))
      (is (= "passkey" (:auth_method (first (query "SELECT * FROM browser_sessions WHERE token_hash = ?" (crypto/digest-token (:token signed-in))))))))
    (let [_ (totp/enroll settings)
          begin (act settings (open) "login/passkey/begin" {"identifier" owner/did})
          response (authenticator/authenticator (get-in begin [:result :options]) :mode "authenticate" :credential credential :counter 3)
          signed-in (act settings begin "login/passkey/finish" {"id" (get-in begin [:result :id]) "response" (json/write-str (get response "response"))})]
      ;; A user-verified passkey is already two factors — the device, and the
      ;; PIN or biometric that unlocked it — so no code is asked on top even
      ;; with an authenticator enrolled.
      (is (= "authenticated" (get-in signed-in [:view :stage])))
      (is (= [] (get-in (act settings signed-in "passkeys/remove" {"id" (get credential "id")}) [:view :passkeys]))))))

(deftest email-factor-and-revocation
  (let [settings (api/settings) _ (owner/account! settings)]
    (owner/mutate "UPDATE accounts SET email_auth_factor = true, email_confirmed = true")
    (let [pending (login settings)
          code (api/email-token "Sign in to your PDS account")
          verified (act settings pending "login/factor" {"code" code})]
      (is (= "factor" (get-in pending [:view :stage])))
      (is (= "email" (get-in pending [:view :factor])))
      (is (= "authenticated" (get-in verified [:view :stage])))
      (is (nil? (get-in (act settings verified "email/disable" {}) [:view :factor]))))))

(deftest http-cookie-origin-csrf-and-assets
  (let [settings (api/settings) _ (owner/account! settings) raw-handler (web/handler fixture/*ds* settings)
        handler #(raw-handler (cond-> % (:body %) (update :body (fn [s] (java.io.ByteArrayInputStream. (.getBytes ^String s "UTF-8"))))))
        session (handler {:request-method :get :uri "/account/session"})
        data (json/read-str (:body session))
        cookie (first (str/split (get-in session [:headers "Set-Cookie"]) #";"))
        request {:request-method :post :uri "/account/action/login/password"
                 :headers {"cookie" cookie "origin" (:public-url settings) "content-type" "application/json" "x-csrf-token" (get data "csrf")}
                 :body (json/write-str owner/credentials)}]
    (is (= "login" (get data "stage")))
    (is (nil? (get data "token")))
    (is (str/starts-with? cookie "__Host-pds-security="))
    (doseq [flag ["HttpOnly" "SameSite=Lax" "Secure" "Path=/"]]
      (is (str/includes? (get-in session [:headers "Set-Cookie"]) flag)))
    (doseq [bad [(update request :headers dissoc "origin")
                 (assoc-in request [:headers "origin"] "https://evil.example")
                 (assoc-in request [:headers "sec-fetch-site"] "cross-site")
                 (update request :headers dissoc "x-csrf-token")]]
      (is (= 403 (:status (handler bad)))))
    (is (= 400 (:status (handler (assoc-in request [:headers "content-type"] "text/plain")))))
    (is (= 401 (:status (handler (assoc-in request [:headers "cookie"] (str cookie "; " cookie))))))
    (doseq [body ["[]" "{" "{} {}"]]
      (is (= 400 (:status (handler (assoc request :body body))))))
    (let [response (handler request)]
      (is (= 200 (:status response)))
      (is (= "authenticated" (get (json/read-str (:body response)) "stage")))
      (is (not= cookie (first (str/split (get-in response [:headers "Set-Cookie"]) #";")))))
    (doseq [[uri mime] [["/account" "text/html"] ["/account/app.js" "text/javascript"] ["/account/style.css" "text/css"]]]
      (let [response (handler {:request-method :get :uri uri})]
        (is (= 200 (:status response)))
        (is (str/starts-with? (get-in response [:headers "Content-Type"]) mime))
        (is (= "no-store" (get-in response [:headers "Cache-Control"])))
        (is (str/includes? (get-in response [:headers "Content-Security-Policy"]) "frame-ancestors 'none'"))))))
