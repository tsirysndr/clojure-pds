(ns pds.service-auth-api-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.plc :as plc]
            [pds.server-api-test :as api]
            [pds.service-auth :as service-auth]
            [pds.service-auth-test :refer [decode upstream!]])
  (:import [java.net.http HttpClient]))
(use-fixtures :each fixture/isolated-database)

(deftest service-authentication-scopes-keys-and-account-lifecycle
  (let [settings (api/settings)
        alice (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "test-password"})
        bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})
        handler (app/handler settings fixture/*ds*) server (http/start! settings handler)
        aud "did:web:destination.example.com#atproto_pds" prefix "com.atproto.server.getServiceAuth?aud=did:web:destination.example.com%23atproto_pds"
        fixtures (atom [])]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client (:port server) %1 %2 %3 %4)
              service #(call "GET" (str prefix %1) nil %2)
              app-token (fn [name privileged]
                          (let [password (get-in (call "POST" "com.atproto.server.createAppPassword" {"name" name "privileged" privileged} (:accessJwt alice)) [:body "password"])]
                            (get-in (call "POST" "com.atproto.server.createSession" {"identifier" (:did alice) "password" password} nil) [:body "accessJwt"])))
              ordinary (app-token "ordinary" false) privileged (app-token "privileged" true)]
          (is (= 401 (:status (service "" nil))))
          (is (= 401 (:status (service "" (:refreshJwt alice)))))
          (is (= 405 (:status (call "POST" prefix {} (:accessJwt alice)))))
          (is (= 400 (:status (call "GET" "com.atproto.server.getServiceAuth" nil (:accessJwt alice)))))
          (is (= 400 (:status (service "&aud=did:web:other.example.com" (:accessJwt alice)))))
          (is (= "no-store" (get-in (handler {:request-method :get :uri "/xrpc/com.atproto.server.getServiceAuth"
                                               :query-string "aud=did:web:destination.example.com"
                                               :headers {"authorization" (str "Bearer " (:accessJwt alice))}}) [:headers "Cache-Control"])))
          (doseq [[session method] [[(:accessJwt alice) "com.atproto.server.createAccount"]
                                    [ordinary "app.bsky.feed.getTimeline"] [privileged "chat.bsky.convo.getMessages"]
                                    [privileged "com.atproto.server.createAccount"] [(:accessJwt bob) "app.bsky.feed.getTimeline"]]]
            (let [response (service (str "&lxm=" method) session) token (get-in response [:body "token"])
                  {:keys [claims message signature]} (decode token) did (get claims "iss")
                  repo (with-open [conn (db/connection fixture/*ds*)] (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" did)))]
              (is (= 200 (:status response)))
              (is (= (if (= session (:accessJwt bob)) (:did bob) (:did alice)) did))
              (is (crypto/verify "ES256" (:public_key repo) message signature))
              ;; A service reference names where to deliver; the token is
              ;; addressed to the service itself, so the fragment is not signed.
              ;; Verbatim, fragment included: the reference verifier compares
              ;; `aud` against the service reference the receiver knows itself
              ;; by, so stripping the fragment made every real appview reject
              ;; the token.
              (is (= aud (get claims "aud")))
              (is (= 401 (:status (call "GET" "com.atproto.server.getSession" nil token))) "Service JWT is not a local session")
              (swap! fixtures conj {:token token :issuer did
                                 :audience aud :method method
                                    :didKey (plc/did-key {:algorithm "ES256" :public (:public_key repo)})})))
          (let [first (get-in (service "" (:accessJwt alice)) [:body "token"])
                second (get-in (service "" (:accessJwt alice)) [:body "token"])]
            (is (not= (get-in (decode first) [:claims "jti"]) (get-in (decode second) [:claims "jti"])))
            (is (not (contains? (:claims (decode first)) "lxm"))))
          (doseq [method ["chat.bsky.convo.getMessages" "CHAT.bsky.convo.GETMESSAGES" "com.atproto.server.createAccount"]]
            (is (= 400 (:status (service (str "&lxm=" method) ordinary)))))
          (doseq [method service-auth/protected-methods session [(:accessJwt alice) ordinary privileged]]
            (is (= 400 (:status (service (str "&lxm=" method) session)))))
          (is (= 200 (:status (call "POST" "com.atproto.server.deactivateAccount" {} (:accessJwt alice)))))
          (is (= 200 (:status (service "&lxm=com.atproto.server.createAccount" (:accessJwt alice)))))
          (is (= 401 (:status (service "&lxm=app.bsky.feed.getTimeline" privileged))))
          (with-open [conn (db/connection fixture/*ds*)] (db/execute! conn "UPDATE accounts SET status = 'taken_down' WHERE did = ?" (:did alice)))
          (is (= 200 (:status (service "&lxm=com.atproto.server.createAccount" (:accessJwt alice)))))
          (is (= "InvalidToken" (get-in (service "&lxm=app.bsky.feed.getTimeline" (:accessJwt alice)) [:body "error"])))
          (is (= "InvalidToken" (get-in (service "" (:accessJwt alice)) [:body "error"])))
          (with-open [conn (db/connection fixture/*ds*)] (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ?" (:did alice)))
          (is (= 401 (:status (service "&lxm=com.atproto.server.createAccount" (:accessJwt alice)))))
          (upstream! @fixtures)))
      (finally ((:stop! server))))))
