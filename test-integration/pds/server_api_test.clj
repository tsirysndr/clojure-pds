(ns pds.server-api-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.auth :as auth]
            [pds.config :as config]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))
(use-fixtures :each fixture/isolated-database)
(defn settings []
  (merge (config/load-config {"PDS_HOSTNAME" "pds.example.com" "PDS_PORT" "0"})
         (accounts/settings {"PDS_USER_DOMAIN" "example.com" "PDS_PUBLIC_URL" "https://pds.example.com" "PDS_ENABLE_SIGNUP" "true"})
         (auth/settings {"PDS_MASTER_KEY" (crypto/b64 (crypto/random-bytes 32))})
         {:email-enabled true}))
(defn call [client port method endpoint body token]
  (let [builder (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port endpoint)))
                    (.timeout (Duration/ofSeconds 20)))
        _ (when token (.header builder "Authorization" (str "Bearer " token)))
        _ (when body (.header builder "Content-Type" "application/json"))
        request (-> builder (.method method (if body (HttpRequest$BodyPublishers/ofString (json/write-str body))
                                                (HttpRequest$BodyPublishers/noBody))) .build)
        response (.send ^HttpClient client request (HttpResponse$BodyHandlers/ofByteArray))
        raw (.body response)
        type (.orElse (.firstValue (.headers response) "Content-Type") "")]
    {:status (.statusCode response) :raw raw
     :body (when (and (pos? (alength raw)) (.startsWith type "application/json")) (json/read-str (String. raw "UTF-8")))}))
(defn xrpc [client port method name body token]
  (call client port method (str "/xrpc/" name) body token))
(defn email-token [subject]
  (with-open [c (db/connection fixture/*ds*)]
    (let [row (last (db/query c "SELECT payload::text FROM email_outbox WHERE payload->>'subject' = ? ORDER BY created_at" subject))
          text (get (json/read-str (:payload row)) "text")]
      (second (re-find #"Your token is: ([A-Za-z0-9_-]+)" text)))))

(deftest account-email-and-password-lifecycle
  (let [settings (settings) handler (app/handler settings fixture/*ds*)
        server (http/start! settings handler) port (:port server)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [created (xrpc client port "POST" "com.atproto.server.createAccount"
                            {"handle" "alice.example.com" "email" "alice@example.com" "password" "correct-password"} nil)
              account (:body created) access (get account "accessJwt") refresh (get account "refreshJwt")]
          (is (= 200 (:status created)))
          (is (= "did:web:alice.example.com" (get account "did")))
          (is (= "https://pds.example.com" (get-in account ["didDoc" "service" 0 "serviceEndpoint"])))
          (is (= 200 (:status (xrpc client port "GET" "com.atproto.server.getSession" nil access))))
          (is (= 401 (:status (xrpc client port "GET" "com.atproto.server.getSession" nil refresh))))
          (is (= 401 (:status (xrpc client port "GET" "com.atproto.server.getSession" nil nil))))
          (is (= 200 (:status (handler {:request-method :get :uri "/.well-known/did.json" :headers {"host" "alice.example.com"}}))))
          (is (= "did:web:alice.example.com" (:body (handler {:request-method :get :uri "/.well-known/atproto-did" :headers {"host" "alice.example.com"}}))))
          (let [token (email-token "Confirm your PDS email")
                body {"email" "alice@example.com" "token" token}]
            (is (string? token))
            (is (= 200 (:status (xrpc client port "POST" "com.atproto.server.confirmEmail" body access))))
            (is (= 400 (:status (xrpc client port "POST" "com.atproto.server.confirmEmail" body access))))
            (is (true? (get-in (xrpc client port "GET" "com.atproto.server.getSession" nil access) [:body "emailConfirmed"]))))
          (let [known (xrpc client port "POST" "com.atproto.server.requestPasswordReset" {"email" "alice@example.com"} nil)
                unknown (xrpc client port "POST" "com.atproto.server.requestPasswordReset" {"email" "missing@example.com"} nil)
                token (email-token "Reset your PDS password")]
            (is (= 200 (:status known) (:status unknown)))
            (is (= (:body known) (:body unknown)))
            (is (= 200 (:status (xrpc client port "POST" "com.atproto.server.resetPassword" {"token" token "password" "new-password"} nil))))
            (is (= 401 (:status (xrpc client port "GET" "com.atproto.server.getSession" nil access))))
            (is (= 401 (:status (xrpc client port "POST" "com.atproto.server.createSession" {"identifier" "alice.example.com" "password" "correct-password"} nil))))
            (is (= 200 (:status (xrpc client port "POST" "com.atproto.server.createSession" {"identifier" "alice@example.com" "password" "new-password"} nil)))))))
      (finally ((:stop! server))))))
