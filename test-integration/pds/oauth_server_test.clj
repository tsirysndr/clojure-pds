(ns pds.oauth-server-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.oauth-tokens-test :as token]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.jose :as jose]
            [pds.oauth.server :as server]
            [pds.proxy-api-test :as wire]
            [pds.rate-limit :as limit])
  (:import [java.net.http HttpClient]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(use-fixtures :each fixture/isolated-database)
(defn settings [env]
  (assoc (:settings env) :oauth-client-fetch (fn [_ _] (metadata/response @(:document env)))))

(deftest discovery-and-routes-over-http
  (let [env (token/env) config (settings env) running (http/start! {:host "127.0.0.1" :port 0} (app/handler config fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(wire/call client (:port running) %1 %2 nil %3 nil)
              auth (call "GET" "/.well-known/oauth-authorization-server" {"X-Forwarded-Host" "evil.example"})
              resource (call "GET" "/.well-known/oauth-protected-resource" {})
              challenge (call "GET" "/xrpc/com.atproto.server.getSession" {})]
          (is (= 200 (:status auth) (:status resource)))
          (is (= "https://pds.example.com" (get-in auth [:body "issuer"]) (get-in resource [:body "resource"])))
          (is (= ["https://pds.example.com"] (get-in resource [:body "authorization_servers"])))
          (is (= true (get-in auth [:body "require_pushed_authorization_requests"])))
          (is (= ["ES256"] (get-in auth [:body "dpop_signing_alg_values_supported"])))
          (is (= ["none" "private_key_jwt"] (get-in auth [:body "token_endpoint_auth_methods_supported"])))
          (is (= ["S256"] (get-in auth [:body "code_challenge_methods_supported"])))
          (is (= 401 (:status challenge)))
          (is (str/includes? (get-in challenge [:headers "www-authenticate"]) "resource_metadata=\"https://pds.example.com/.well-known/oauth-protected-resource\""))
          (doseq [path server/metadata-paths]
            (let [head (call "HEAD" path {})]
              (is (= 200 (:status head)))
              (is (zero? (alength ^bytes (:raw head)))))
            (is (= 204 (:status (call "OPTIONS" path {}))))
            (is (= 405 (:status (call "POST" path {})))))
          (doseq [path server/protocol-paths]
            (let [preflight (call "OPTIONS" path {"Origin" "https://app.example.com"})
                  invalid (call "POST" path {})]
              (is (= 204 (:status preflight)))
              (is (= "*" (get-in preflight [:headers "access-control-allow-origin"])))
              (is (string? (get-in preflight [:headers "dpop-nonce"])))
              (is (= 400 (:status invalid)))
              (is (= "invalid_request" (get-in invalid [:body "error"])))
              (is (= 405 (:status (call "GET" path {}))))))
          (is (= 400 (:status (call "GET" "/oauth/authorize" {}))))
          (is (nil? (get-in (call "GET" "/account" {}) [:headers "access-control-allow-origin"])))))
      (finally ((:stop! running))))))

(deftest rate-limit-rejections-preserve-oauth-transport-headers
  (let [env (token/env)]
    (doseq [path (conj server/protocol-paths "/xrpc/com.atproto.server.getSession")
            unavailable? [false true]]
      (let [limiter (if unavailable? (reify limit/Limiter (admit! [_ _] (throw (ex-info "Offline" {}))))
                        (limit/memory-limiter {:max-requests 1 :window-ms 60000}))
            handler (app/handler (assoc (settings env) :rate-limiter limiter) fixture/*ds*)
            request {:request-method :post :uri path :headers {"dpop" "invalid"} :remote-addr "127.0.0.1"}
            _ (handler request) response (handler request)]
        (is (= (if unavailable? 503 429) (:status response)))
        (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"])))
        (is (string? (get-in response [:headers "DPoP-Nonce"])))
        (is (= "no-store" (get-in response [:headers "Cache-Control"])))
        (is (str/includes? (get-in response [:headers "Access-Control-Expose-Headers"]) "Retry-After"))))))

(defn upstream! [value]
  (let [path (Files/createTempFile "pds-oauth-" ".json" (make-array FileAttribute 0))]
    (try
      (spit (.toFile path) (json/write-str value))
      (let [process (.start (doto (ProcessBuilder. ^java.util.List ["node" "scripts/conformance/verify-oauth.mjs" (str path)])
                             (.redirectErrorStream true)))
            finished (.waitFor process 60 TimeUnit/SECONDS)]
        (when-not finished (.destroyForcibly process))
        (is finished "Upstream OAuth client must finish within 60 seconds")
        (when finished
          (let [output (slurp (.getInputStream process))]
            (is (zero? (.exitValue process)) output)
            (when (zero? (.exitValue process)) (json/read-str output)))))
      (finally (Files/deleteIfExists path)))))

(defn upstream-flow! [confidential?]
  (let [env (token/env confidential?)
        _ (swap! (:document env) merge (if confidential?
            {"scope" "atproto transition:generic transition:email" "token_endpoint_auth_signing_alg" "ES256"}
            {"token_endpoint_auth_method" "none"}))
        config (settings env) key (:client-key env)
        handle (if confidential? "alice.example.com" "new.example.com") did (str "did:web:" handle)
        running (http/start! {:host "127.0.0.1" :port 0} (app/handler config fixture/*ds*))]
    (try
      (let [result (upstream! (cond-> {:origin (:public-url config) :transport (str "http://127.0.0.1:" (:port running))
                                      :metadata @(:document env) :did did :handle handle :signup (not confidential?)
                                      :email (if confidential? "alice@example.com" "new@example.com")
                                      :password (if confidential? "correct-password" "signup-password")}
                               key (assoc :privateJwk (assoc (jose/public-jwk (:public key)) "d" (crypto/b64 (:private key))
                                                           "kid" (:kid key) "alg" "ES256"))))]
        (is (= did (get result "did")))
        (is (= true (get result "refreshed") (get result "revoked")))
        (is (= 1 (count (token/query "SELECT * FROM records WHERE did = ? AND collection = 'com.example.note' AND rkey = 'upstream-oauth'" did))))
        (is (= [{:revoke_reason "client_revoked"}]
               (token/query "SELECT revoke_reason FROM oauth_sessions WHERE did = ?" did)))
        (is (= (if confidential? 1 0) (count (token/query "SELECT * FROM sessions WHERE did = ?" did)))))
      (finally ((:stop! running))))))

(when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
  (deftest upstream-public-signup (upstream-flow! false))
  (deftest upstream-confidential-login (upstream-flow! true)))
