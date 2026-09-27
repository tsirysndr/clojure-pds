(ns pds.preferences-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.migration-activation-test :as activation]
            [pds.oauth-permissions-test :as scoped]
            [pds.oauth-resource-test :as resource]
            [pds.oauth-tokens-test :as token]
            [pds.preferences :as preferences]
            [pds.proxy-api-test :as wire]
            [pds.proxy-test :as upstream]
            [pds.repo-import-test :as imports]
            [pds.server-api-test :as api]
            [pds.service-auth-test :as jwt])
  (:import [java.io InputStream]
           [java.net.http HttpClient]
           [java.time LocalDate]))

(use-fixtures :each fixture/isolated-database)
(def get-id "app.bsky.actor.getPreferences")
(def put-id "app.bsky.actor.putPreferences")
(def details {"$type" preferences/personal "birthDate" "2010-09-28T00:00:00Z"})
(def adult {"$type" "app.bsky.actor.defs#adultContentPref" "enabled" false})
(def future-pref {"$type" "app.bsky.future.setting" "unknown" {"nested" [1 true nil]}})
(def local-audience "did:web:appview.example.com#bsky_appview")
(defn public-state [] [(token/query "SELECT head, rev FROM repositories ORDER BY did")
                       (token/query "SELECT count(*) AS n FROM repo_events")
                       (token/query "SELECT count(*) AS n FROM repo_blocks")])

(deftest private-roundtrip-validation-isolation-and-app-password-boundary
  (let [settings (api/settings) alice (imports/local! settings)
        bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)
        before (public-state)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [get-prefs #(api/xrpc client port "GET" get-id nil %)
              put-prefs #(api/xrpc client port "POST" put-id {"preferences" %1} %2)
              access (:accessJwt alice)
              password (get-in (api/xrpc client port "POST" "com.atproto.server.createAppPassword" {"name" "prefs"} access) [:body "password"])
              app-token (get-in (api/xrpc client port "POST" "com.atproto.server.createSession" {"identifier" (:did alice) "password" password} nil) [:body "accessJwt"])]
          (is (= 401 (:status (get-prefs nil))))
          (is (= [] (get-in (get-prefs access) [:body "preferences"])))
          (is (= 200 (:status (put-prefs [details adult future-pref future-pref] access))))
          (with-redefs [preferences/today #(LocalDate/parse "2026-09-27")]
            (let [expected-age {"$type" preferences/declared-age "isOverAge13" true "isOverAge16" false "isOverAge18" false}]
              (is (= [details adult future-pref future-pref expected-age] (get-in (get-prefs access) [:body "preferences"])))
              (is (= [adult future-pref future-pref expected-age] (get-in (get-prefs app-token) [:body "preferences"])))))
          (is (= [] (get-in (get-prefs (:accessJwt bob)) [:body "preferences"])))
          (is (= 403 (:status (put-prefs [details] app-token))))
          (doseq [bad [[{"$type" "com.other.preference"}] [{}]
                       [{"$type" "app.bsky.actor.defs#adultContentPref" "enabled" "yes"}]
                       [{"$type" preferences/personal "birthDate" "not-a-date"}]
                       [{"$type" "app.bsky.actor.defs#main"}] nil]]
            (is (= 400 (:status (put-prefs bad access)))))
          (is (= 5 (count (get-in (get-prefs access) [:body "preferences"]))) "Invalid replacement leaves the old values intact")
          (is (= 400 (:status (put-prefs (vec (repeat 1001 future-pref)) access))))
          (is (= 200 (:status (put-prefs [] app-token))))
          (is (= details (first (get-in (get-prefs access) [:body "preferences"]))) "Restricted replacement retains hidden personal details")
          (is (= 2 (count (get-in (get-prefs access) [:body "preferences"]))))
          (is (= 200 (:status (put-prefs [{"$type" preferences/declared-age "isOverAge18" true}] access))))
          (is (= [] (get-in (get-prefs access) [:body "preferences"])) "Derived age cannot be forged or stored")
          (is (= 200 (:status (put-prefs [future-pref] access))))
          (with-open [conn (db/connection fixture/*ds*)]
            (is (= [future-pref] (:preferences (preferences/read! conn {:did (:did alice) :access-scope "com.atproto.access"})))))
          (is (= before (public-state)) "Preferences never change repository blocks, commits or firehose events")))
      (finally ((:stop! server))))))

(deftest inactive-migration-transfers-preferences-and-counts-private-state
  (let [base (api/settings) {:keys [account source]} (imports/prepare! base)
        settings (activation/web-settings base (atom (jwt/document (:did account) (:key source))))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client (:port server) %1 %2 %3 (:accessJwt account))]
          (is (= 200 (:status (call "POST" put-id {"preferences" [details future-pref]}))))
          (is (= [details future-pref] (vec (take 2 (get-in (call "GET" get-id nil) [:body "preferences"])))))
          (is (= 2 (get-in (call "GET" "com.atproto.server.checkAccountStatus" nil) [:body "privateStateValues"])))
          (is (= 200 (:status (call "POST" put-id {"preferences" []}))))
          (is (= 0 (get-in (call "GET" "com.atproto.server.checkAccountStatus" nil) [:body "privateStateValues"])))
          (db/transact! fixture/*ds* #(db/execute! % "UPDATE accounts SET status = 'taken_down' WHERE did = ?" (:did account)))
          (is (= 200 (:status (call "GET" get-id nil))))
          (is (= 401 (:status (call "POST" put-id {"preferences" [future-pref]}))))
          (is (empty? (token/query "SELECT * FROM repo_events")))) )
      (finally ((:stop! server))))))

(deftest oauth-method-audience-and-personal-details-boundaries
  (let [env (token/env) read-scope (str "atproto rpc:" get-id "?aud=" local-audience)
        read-token (scoped/mint! env read-scope)
        both (scoped/mint! env (str read-scope " rpc:" put-id "?aud=" local-audience))
        wrong (scoped/mint! env (str "atproto rpc:" get-id "?aud=did:web:other.example.com#bsky_appview"))
        local (scoped/mint! env (str "atproto rpc:" get-id "?aud=did:web:pds.example.com#atproto_pds"))
        generic (scoped/mint! env "atproto transition:generic")
        handler (app/handler (assoc (resource/settings env) :proxy-appview-service local-audience) fixture/*ds*)
        call (fn [issued method id body] (resource/call handler (resource/request env issued method (str "/xrpc/" id)) body))]
    (db/transact! fixture/*ds* #(do (db/query % "SELECT did FROM accounts FOR UPDATE")
                                   (preferences/put! % {:did "did:web:alice.example.com" :access-scope "com.atproto.access"} [details])))
    (is (= 200 (:status (call read-token :get get-id nil))))
    (is (= 403 (:status (call local :get get-id nil))))
    (is (= 200 (:status (resource/call (app/handler (resource/settings env) fixture/*ds*)
                                      (resource/request env local :get (str "/xrpc/" get-id))))))
    (is (= "no-store" (get-in (call read-token :get get-id nil) [:headers "Cache-Control"])))
    (is (not-any? #(= preferences/personal (get % "$type")) (get-in (call read-token :get get-id nil) [:json "preferences"])))
    (is (= 403 (:status (call wrong :get get-id nil))))
    (is (= 403 (:status (handler (assoc (resource/request env read-token :post (str "/xrpc/" put-id))
                                      :body (proxy [InputStream] [] (read [] (throw (AssertionError. "Denied preference body was read")))))))))
    (is (= 403 (:status (call both :post put-id {"preferences" [details]}))))
    (is (= 200 (:status (call both :post put-id {"preferences" [adult]}))))
    (is (= 200 (:status (call generic :get get-id nil))))
    (is (= [adult] (vec (remove #(= preferences/declared-age (get % "$type"))
                               (get-in (call both :get get-id nil) [:json "preferences"])))))
    (db/transact! fixture/*ds* #(db/execute! % "UPDATE oauth_sessions SET revoked_at = now()"))
    (is (= 401 (:status (call both :post put-id {"preferences" []}))))))

(deftest explicit-other-appview-is-proxied-without-touching-local-preferences
  (upstream/with-service
    (fn [{:keys [client fetch calls mode]}]
      (let [settings (assoc (api/settings) :http-client client :fetch fetch :proxy-appview-service local-audience)
            account (imports/local! settings) server (http/start! settings (app/handler settings fixture/*ds*))]
        (reset! mode :error)
        (try
          (with-open [client (HttpClient/newHttpClient)]
            (is (= 200 (:status (api/xrpc client (:port server) "POST" put-id {"preferences" [future-pref]} (:accessJwt account)))))
            (is (= 429 (:status (wire/call client (:port server) "GET" (str "/xrpc/" get-id) (:accessJwt account)
                                         {"atproto-proxy" upstream/audience} nil))))
            (let [claims (:claims (jwt/decode (subs (get-in (last @calls) [:headers "authorization"]) 7)))]
              (is (= get-id (get claims "lxm")))
              (is (= upstream/audience (get claims "aud"))))
            (is (= [future-pref] (get-in (api/xrpc client (:port server) "GET" get-id nil (:accessJwt account)) [:body "preferences"])))
            (is (= 1 (count @calls))))
          (finally ((:stop! server))))))))
