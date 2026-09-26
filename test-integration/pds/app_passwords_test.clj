(ns pds.app-passwords-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.auth :as auth]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.server-api-test :as api]
            [pds.http :as http])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)

(deftest app-password-session-lifecycle
  (let [settings (api/settings)
        alice (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "test-password"})
        bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client port %1 (str "com.atproto.server." %2) %3 %4)
              primary (:accessJwt alice)
              created (call "POST" "createAppPassword" {"name" "phone"} primary)
              privileged (call "POST" "createAppPassword" {"name" "desktop" "privileged" true} primary)
              password (get-in created [:body "password"])
              login #(call "POST" "createSession" {"identifier" "alice.example.com" "password" %} nil)
              session (:body (login password))
              privileged-session (:body (login (get-in privileged [:body "password"])))
              access (get session "accessJwt") refresh (get session "refreshJwt")]
          (is (= 200 (:status created) (:status privileged)))
          (is (re-matches #"[a-z2-7]{4}(?:-[a-z2-7]{4}){3}" password))
          (is (false? (get-in created [:body "privileged"])))
          (is (true? (get-in privileged [:body "privileged"])))
          (is (= "com.atproto.appPass" (get (auth/verify-jwt settings "at+jwt" access) "scope")))
          (is (= "com.atproto.appPassPrivileged" (get (auth/verify-jwt settings "at+jwt" (get privileged-session "accessJwt")) "scope")))
          (is (= 200 (:status (login (str/upper-case (str/replace password "-" ""))))))
          (is (= 401 (:status (login "aaaa-bbbb-cccc-dddd"))))
          (is (= 401 (:status (call "POST" "createSession" {"identifier" "bob.example.com" "password" password} nil))))
          (is (= 400 (:status (call "POST" "createAppPassword" {"name" "phone"} primary))))
          (is (= 400 (:status (call "POST" "createAppPassword" {"name" "wrong" "privileged" nil} primary))))
          (doseq [token [access (get privileged-session "accessJwt")]]
            (is (= 403 (:status (call "POST" "createAppPassword" {"name" "escalate"} token))))
            (is (= 200 (:status (call "GET" "getSession" nil token))))
            (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.repo.createRecord"
                                        {"repo" (:did alice) "collection" "app.bsky.feed.post"
                                         "record" {"$type" "app.bsky.feed.post" "text" "App password post" "createdAt" "2026-09-26T00:00:00Z"}}
                                        token)))))
          (let [listed (get-in (call "GET" "listAppPasswords" nil access) [:body "passwords"])]
            (is (= #{"phone" "desktop"} (set (map #(get % "name") listed))))
            (is (every? #(= #{"name" "privileged" "createdAt"} (set (keys %))) listed)))
          (is (= [] (get-in (call "GET" "listAppPasswords" nil (:accessJwt bob)) [:body "passwords"])))
          (with-open [conn (db/connection fixture/*ds*)]
            (is (every? #(and (not= password (:password_digest %))
                              (not= (str/replace password "-" "") (:password_digest %)))
                        (db/query conn "SELECT password_digest FROM app_passwords"))))
          (let [rotated (call "POST" "refreshSession" nil refresh)
                rotated-access (get-in rotated [:body "accessJwt"])
                rotated-refresh (get-in rotated [:body "refreshJwt"])
                privileged-rotated (call "POST" "refreshSession" nil (get privileged-session "refreshJwt"))]
            (is (= 200 (:status rotated) (:status privileged-rotated)))
            (is (= "com.atproto.appPass" (get (auth/verify-jwt settings "at+jwt" rotated-access) "scope")))
            (is (= "com.atproto.appPassPrivileged"
                   (get (auth/verify-jwt settings "at+jwt" (get-in privileged-rotated [:body "accessJwt"])) "scope")))
            (is (= 403 (:status (call "POST" "createAppPassword" {"name" "escalate"} rotated-access))))
            ;; A correctly signed token cannot promote the persisted session.
            (let [claims (auth/verify-jwt settings "at+jwt" rotated-access)
                  forged (auth/jwt settings "at+jwt" (assoc claims "scope" "com.atproto.access"))]
              (is (= 401 (:status (call "POST" "createAppPassword" {"name" "escalate"} forged)))))
            ;; Other accounts cannot revoke it; app sessions may list/revoke their
            ;; own account's passwords, matching the upstream endpoint behavior.
            (is (= 200 (:status (call "POST" "revokeAppPassword" {"name" "phone"} (:accessJwt bob)))))
            (is (= 200 (:status (call "GET" "getSession" nil rotated-access))))
            (is (= 200 (:status (call "POST" "revokeAppPassword" {"name" "phone"} rotated-access))))
            (doseq [token [access rotated-access]] (is (= 401 (:status (call "GET" "getSession" nil token)))))
            (is (= 401 (:status (call "POST" "refreshSession" nil rotated-refresh))))
            (is (= 401 (:status (login password))))
            (is (= 200 (:status (call "GET" "getSession" nil primary))))
            (is (= 200 (:status (call "GET" "getSession" nil (get-in privileged-rotated [:body "accessJwt"]))))))
          (is (= 200 (:status (call "POST" "requestPasswordReset" {"email" "alice@example.com"} nil))))
          (let [email-token (api/email-token "Reset your PDS password")]
            (is (= 200 (:status (call "POST" "resetPassword" {"token" email-token "password" "new-password"} nil))))
            (is (= 401 (:status (login (get-in privileged [:body "password"])))))
            (is (= 401 (:status (call "GET" "getSession" nil primary))))
            (is (= 200 (:status (login "new-password")))))
          (with-open [conn (db/connection fixture/*ds*)]
            (is (= 0 (:count (first (db/query conn "SELECT count(*) AS count FROM app_passwords WHERE did = ?" (:did alice)))))))))
      (finally ((:stop! server))))))
