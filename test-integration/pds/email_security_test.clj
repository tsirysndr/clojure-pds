(ns pds.email-security-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)

(deftest email-change-and-authentication-factor
  (let [settings (api/settings)
        _ (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})
        alice (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "test-password"})
        confirmation (api/email-token "Confirm your PDS email")
        did (:did alice) access (:accessJwt alice)
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client port %1 (str "com.atproto.server." %2) %3 %4)
              login #(call "POST" "createSession" % nil)
              credentials {"identifier" did "password" "test-password"}
              second-session (:body (login credentials))
              app-password (get-in (call "POST" "createAppPassword" {"name" "phone"} access) [:body "password"])
              app-login (login (assoc credentials "password" app-password))
              app-access (get-in app-login [:body "accessJwt"])]
          (is (false? (get-in (call "POST" "requestEmailUpdate" nil access) [:body "tokenRequired"])))
          (is (= 403 (:status (call "POST" "requestEmailUpdate" nil app-access))))
          (is (= 403 (:status (call "POST" "updateEmail" {"email" "attacker@example.com"} app-access))))
          (is (= 400 (:status (call "POST" "updateEmail" {"email" "alice@example.com" "emailAuthFactor" true} access))))
          (is (= 200 (:status (call "POST" "confirmEmail" {"email" "alice@example.com" "token" confirmation} access))))
          (is (= 200 (:status (call "POST" "requestPasswordReset" {"email" "alice@example.com"} nil))))
          (let [old-reset (api/email-token "Reset your PDS password")
                requested (call "POST" "requestEmailUpdate" nil access)
                token (api/email-token "Update your PDS email")]
            (is (true? (get-in requested [:body "tokenRequired"])))
            (is (= "TokenRequired" (get-in (call "POST" "updateEmail" {"email" "new@example.com"} access) [:body "error"])))
            (is (= 400 (:status (call "POST" "updateEmail" {"email" "new@example.com" "token" "wrong"} access))))
            (is (= 400 (:status (call "POST" "updateEmail" {"email" "bob@example.com" "token" token} access))))
            ;; The failed uniqueness check must not consume the proof.
            (is (= 200 (:status (call "POST" "updateEmail" {"email" "NEW@example.com" "token" token} access))))
            (is (= 400 (:status (call "POST" "resetPassword" {"token" old-reset "password" "evil-new-password"} nil)))))
          (is (= 401 (:status (call "GET" "getSession" nil (get second-session "accessJwt")))))
          (is (= 401 (:status (call "GET" "getSession" nil app-access))))
          (is (= 401 (:status (login (assoc credentials "identifier" "alice@example.com")))))
          (is (= 200 (:status (login (assoc credentials "identifier" "new@example.com")))))
          (let [session (call "GET" "getSession" nil access)]
            (is (= "new@example.com" (get-in session [:body "email"])))
            (is (false? (get-in session [:body "emailConfirmed"]))))
          (is (= 200 (:status (call "POST" "confirmEmail" {"email" "new@example.com" "token" (api/email-token "Confirm your PDS email")} access))))
          (is (= 200 (:status (call "POST" "requestEmailUpdate" nil access))))
          (is (= 200 (:status (call "POST" "updateEmail" {"email" "new@example.com" "emailAuthFactor" true
                                                          "token" (api/email-token "Update your PDS email")} access))))
          (is (true? (get-in (call "GET" "getSession" nil access) [:body "emailAuthFactor"])))
          (dotimes [_ 2]
            (let [challenge (login credentials)]
              (is (= 401 (:status challenge)))
              (is (= "AuthFactorTokenRequired" (get-in challenge [:body "error"])))))
          (with-open [conn (db/connection fixture/*ds*)]
            (is (= 1 (:count (first (db/query conn "SELECT count(*) AS count FROM account_tokens WHERE did = ? AND purpose = 'sign-in'" did))))))
          (let [token (api/email-token "Sign in to your PDS account")]
            (is (= 400 (:status (login (assoc credentials "authFactorToken" "wrong")))))
            (is (= 200 (:status (login (assoc credentials "authFactorToken" token)))))
            (is (= 400 (:status (login (assoc credentials "authFactorToken" token))))))
          ;; Existing app credentials already represent delegated authorization.
          (is (= 200 (:status (login (assoc credentials "password" app-password)))))
          (is (= 200 (:status (call "POST" "requestEmailUpdate" nil access))))
          (is (= 200 (:status (call "POST" "updateEmail" {"email" "new@example.com" "emailAuthFactor" false
                                                          "token" (api/email-token "Update your PDS email")} access))))
          (is (= 200 (:status (login credentials))))))
      (finally ((:stop! server))))))
