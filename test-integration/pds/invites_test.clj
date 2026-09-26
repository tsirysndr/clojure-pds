(ns pds.invites-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.admin-test :as admin-test]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.invites :as invites]
            [pds.repo :as repo]
            [pds.server-api-test :as api])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(use-fixtures :each fixture/isolated-database)

(def admin-password "invite-test-admin-password")
(defn admin-call
  ([client port name body] (admin-call client port "POST" name body))
  ([client port method name body]
  (let [request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/xrpc/" name)))
                    (.header "Authorization" (admin-test/basic (str "admin:" admin-password)))
                    (.header "Content-Type" "application/json")
                    (.method method (if body (HttpRequest$BodyPublishers/ofString (json/write-str body))
                                        (HttpRequest$BodyPublishers/noBody))) .build)
        response (.send ^HttpClient client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (when (seq (.body response)) (json/read-str (.body response)))})))

(defn signup [name code]
  (cond-> {"handle" (str name ".example.com") "email" (str name "@example.com") "password" "signup-password"}
    code (assoc "inviteCode" code)))

(deftest invitation-http-boundaries
  (let [settings (assoc (api/settings) :invite-required true :admin-password admin-password)
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [post #(api/xrpc client port "POST" %1 %2 %3)
              admin #(admin-call client port %1 %2)
              created (admin "com.atproto.server.createInviteCode" {"useCount" 2})
              code (get-in created [:body "code"])]
          (is (= 200 (:status created)))
          (is (true? (get-in (api/xrpc client port "GET" "com.atproto.server.describeServer" nil nil) [:body "inviteCodeRequired"])))
          (is (= 401 (:status (post "com.atproto.server.createInviteCode" {"useCount" 2} nil))))
          (doseq [n [0 -1 1.5 "1" nil 1000001]]
            (is (= 400 (:status (admin "com.atproto.server.createInviteCode" {"useCount" n})))))
          (is (= "InvalidInviteCode" (get-in (post "com.atproto.server.createAccount" (signup "missing" nil) nil) [:body "error"])))
          (is (= 400 (:status (post "com.atproto.server.createAccount" (signup "wrong" "unknown-code") nil))))
          (let [a (post "com.atproto.server.createAccount" (signup "alice" code) nil)
                did (get-in a [:body "did"]) token (get-in a [:body "accessJwt"])
                gift (admin "com.atproto.server.createInviteCodes" {"codeCount" 2 "useCount" 1 "forAccounts" [did]})
                gift-codes (get-in gift [:body "codes" 0 "codes"])]
            (is (= 200 (:status a) (:status gift)))
            (is (= 2 (count gift-codes)))
            (is (= 401 (:status (post "com.atproto.server.createInviteCode" {"useCount" 1} token))))
            (is (= 400 (:status (post "com.atproto.server.createAccount" (signup "alice" code) nil))))
            (is (= 200 (:status (post "com.atproto.server.createAccount" (signup "bob" code) nil))))
            (is (= 400 (:status (post "com.atproto.server.createAccount" (signup "exhausted" code) nil))))
            (let [listed (api/xrpc client port "GET" "com.atproto.server.getAccountInviteCodes" nil token)]
              (is (= (set gift-codes) (set (map #(get % "code") (get-in listed [:body "codes"]))))))
            (is (= 200 (:status (admin "com.atproto.admin.disableAccountInvites" {"account" did "note" "No automatic grants"}))))
            ;; Disabling future grants must not invalidate already issued codes.
            (is (= 200 (:status (post "com.atproto.server.createAccount" (signup "carol" (first gift-codes)) nil))))
            (let [unused (get-in (api/xrpc client port "GET" "com.atproto.server.getAccountInviteCodes?includeUsed=false" nil token) [:body "codes"])]
              (is (= [(second gift-codes)] (mapv #(get % "code") unused))))
            (is (= 200 (:status (admin "com.atproto.admin.disableInviteCodes" {"accounts" [did]}))))
            (is (= 400 (:status (post "com.atproto.server.createAccount" (signup "disabled" (second gift-codes)) nil))))
            (is (= 200 (:status (admin "com.atproto.admin.enableAccountInvites" {"account" did}))))
            (with-open [conn (db/connection fixture/*ds*)]
              (is (= 3 (:n (first (db/query conn "SELECT count(*) AS n FROM invite_uses")))))
              (is (= 3 (:n (first (db/query conn "SELECT count(*) AS n FROM accounts")))))
              (is (false? (:invites_disabled (first (db/query conn "SELECT invites_disabled FROM accounts WHERE did = ?" did)))))))))
      (finally ((:stop! server))))))

(deftest last-invitation-use-is-atomic
  (let [ds fixture/*ds* settings (assoc (api/settings) :invite-required true)
        code (db/transact! ds #(invites/create! % "admin" 1))
        ready (java.util.concurrent.CountDownLatch. 2) start (promise)
        attempt (fn [name]
                  (future (.countDown ready) @start
                          (try (accounts/create! ds settings (signup name code)) :created
                               (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))
        a (attempt "racer-a") b (attempt "racer-b")]
    (.await ready) (deliver start true)
    (is (= #{:created "InvalidInviteCode"} #{@a @b}))
    (with-open [conn (db/connection ds)]
      (is (= 1 (:n (first (db/query conn "SELECT count(*) AS n FROM accounts")))))
      (is (= 1 (:n (first (db/query conn "SELECT count(*) AS n FROM invite_uses"))))))))

(deftest failed-initialization-does-not-consume-an-invite
  (let [ds fixture/*ds* settings (assoc (api/settings) :invite-required true)
        code (db/transact! ds #(invites/create! % "admin" 1))]
    (with-redefs [repo/initialize! (fn [& _] (throw (ex-info "Simulated repository failure" {})))]
      (is (thrown? clojure.lang.ExceptionInfo (accounts/create! ds settings (signup "rollback" code)))))
    (with-open [conn (db/connection ds)]
      (is (= 0 (:n (first (db/query conn "SELECT count(*) AS n FROM invite_uses")))))
      (is (= 0 (:n (first (db/query conn "SELECT count(*) AS n FROM accounts"))))))
    (is (string? (:did (accounts/create! ds settings (signup "retry" code)))))))
