(ns pds.oauth-sessions-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.browser-security-test :as browser-test]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-par-test :as par-test]
            [pds.oauth-tokens-test :as token]
            [pds.oauth-web-test :as web-test]
            [pds.oauth.client-auth-test :as client-auth]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof]
            [pds.oauth.sessions :as sessions]
            [pds.oauth.tokens :as tokens]
            [pds.proxy-api-test :as wire]
            [pds.security.browser :as browser]
            [pds.totp-test :as totp])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)
(defn revoke-proof [env] (proof/sign (:key env) (assoc (proof/claims) "htu" (str (get-in env [:settings :public-url]) "/oauth/revoke"))))
(defn revoke
  ([env value] (revoke env value {}))
  ([env value overrides]
   (tokens/revoke-token! fixture/*ds* (:resolver env) (:settings env)
                         (merge (token/credentials env) {"token" value} overrides) (revoke-proof env))))
(defn issue [env] (token/issue env (token/approved env)))
(defn list-owner [state] (get-in state [:view :oauth-sessions :items]))

(deftest revocation-of-either-token-cascades-to-the-whole-family
  (let [env (token/env)]
    (doseq [field [:access_token :refresh_token]]
      (let [issued (issue env) rotated (token/issue env (token/refresh-params issued))]
        (is (nil? (revoke env (get issued field) {"token_type_hint" "unknown-hint"})))
        (is (= "invalid_grant" (proof/error #(token/access issued))))
        (is (= "invalid_grant" (proof/error #(token/access rotated))))
        (is (= "invalid_grant" (proof/error #(token/issue env (token/refresh-params rotated)))))
        (is (nil? (revoke env (get issued field))))))
    (is (= #{"client_revoked"} (set (map :revoke_reason (token/query "SELECT * FROM oauth_sessions")))))
    (is (nil? (revoke env "unknown-token")))
    (is (= "invalid_request" (proof/error #(revoke env ""))))
    (is (= "invalid_request" (proof/error #(revoke env nil))))
    (let [issued (issue env)]
      (owner/mutate "UPDATE accounts SET status = 'deactivated'")
      (with-redefs [dpop/now (constantly (+ (dpop/now) (* 15 86400)))]
        (is (nil? (revoke env (:refresh_token issued)))))
      (is (= "client_revoked" (:revoke_reason (last (token/query "SELECT * FROM oauth_sessions ORDER BY created_at"))))))))

(deftest revoked-token-ownership-client-binding-and-replay
  (let [env (token/env true) issued (issue env) another (client-auth/new-key)
        proof (revoke-proof env) params (merge (token/credentials env) {"token" (:refresh_token issued)})]
    (is (= "invalid_dpop_proof" (proof/error #(revoke (assoc env :key (crypto/keypair)) (:refresh_token issued)))) )
    (is (= "invalid_grant"
           (proof/error #(revoke (assoc env :client-key nil) (:refresh_token issued) {"client_id" "http://localhost"})))
        "Public credentials cannot revoke another client's session")
    (swap! (:document env) assoc "jwks" (get (client-auth/document [(:client-key env) another]) "jwks"))
    (is (= "invalid_client" (proof/error #(revoke (assoc env :client-key another) (:access_token issued)))))
    (is (= owner/did (:did (token/access issued))))
    (is (nil? (tokens/revoke-token! fixture/*ds* (:resolver env) (:settings env) params proof)))
    (is (= "invalid_dpop_proof" (proof/error #(tokens/revoke-token! fixture/*ds* (:resolver env) (:settings env) params proof))))
    (let [issued (issue env) saved @(:document env)]
      (reset! (:document env) (client-auth/document [another]))
      (is (= "invalid_client" (proof/error #(revoke env (:refresh_token issued)))))
      (reset! (:document env) saved)
      (is (= "invalid_grant" (proof/error #(token/access issued))))
      (is (some #(= "client_key_removed" (:revoke_reason %)) (token/query "SELECT * FROM oauth_sessions"))))))

(deftest refresh-and-revoke-serialize-with-no-surviving-grants
  (let [env (token/env) issued (issue env) gate (promise)
        refreshed (future @gate (try (token/issue env (token/refresh-params issued)) (catch clojure.lang.ExceptionInfo _ nil)))
        revoked (future @gate (revoke env (:refresh_token issued)))]
    (deliver gate true)
    (is (nil? (deref revoked 15000 :timeout)))
    (let [result (deref refreshed 15000 :timeout)]
      (is (not= :timeout result))
      (when result (is (= "invalid_grant" (proof/error #(token/access result))))))
    (is (= "invalid_grant" (proof/error #(token/access issued))))
    (is (= "client_revoked" (:revoke_reason (first (token/query "SELECT * FROM oauth_sessions")))))))

(deftest owners-can-inspect-and-revoke-only-their-own-sessions
  (let [env (token/env) issued (issue env) keep (issue env) state (browser-test/login (:settings env))
        id (:session-id (token/access issued))
        another (accounts/register! fixture/*ds* (:settings env) {"handle" "other.example.com" "email" "other@example.com" "password" "correct-password"})
        foreign (crypto/token)]
    (owner/mutate "INSERT INTO oauth_sessions(session_id, code_hash, did, account_epoch, client_id, snapshot, created_at, expires_at)
                   SELECT ?, ?, ?, 0, client_id, snapshot, created_at, expires_at FROM oauth_sessions WHERE session_id = ?"
                  foreign (crypto/token) (:did another) id)
    (is (= 2 (count (list-owner state))))
    (is (= #{:id :client-id :scope :created-at :expires-at} (set (keys (first (list-owner state))))))
    (is (= "atproto transition:generic" (:scope (first (list-owner state)))))
    (is (= 2 (count (list-owner (browser-test/act (:settings env) state "oauth/revoke" {"id" foreign})))))
    (is (nil? (:revoked_at (first (token/query "SELECT * FROM oauth_sessions WHERE session_id = ?" foreign)))))
    (let [updated (browser-test/act (:settings env) state "oauth/revoke" {"id" id})]
      (is (= 1 (count (list-owner updated))))
      (is (= "invalid_grant" (proof/error #(token/access issued))))
      (is (= owner/did (:did (token/access keep))))
      (is (= (:token state) (:token updated)))
      (is (true? (get-in (browser-test/act (:settings env) updated "oauth/revoke" {"id" id}) [:result :revoked]))))
    (is (= "InvalidCsrf" (owner/error #(browser/action! fixture/*ds* (:settings env) (:token state) (crypto/token) "oauth/revoke" {"id" foreign}))))
    (is (= "BrowserSessionRequired" (owner/error #(browser-test/act (:settings env) (browser-test/open) "oauth/list" {}))))
    (owner/mutate "UPDATE accounts SET email_confirmed = true WHERE did = ?" owner/did)
    (let [fresh (browser-test/login (:settings env))]
      (is (empty? (list-owner fresh)))
      (is (= "BrowserSessionRequired" (owner/error #(browser-test/act (:settings env) state "oauth/list" {})))))))

(deftest session-pagination-is-bounded-stable-and-hides-expired-grants
  (let [env (token/env) issued (issue env) id (:session-id (token/access issued))]
    (dotimes [_ 24]
      (owner/mutate "INSERT INTO oauth_sessions(session_id, code_hash, did, account_epoch, client_id, snapshot, created_at, expires_at)
                     SELECT ?, ?, did, account_epoch, client_id, snapshot, created_at, expires_at FROM oauth_sessions WHERE session_id = ?"
                    (crypto/token) (crypto/token) id))
    (let [state (browser-test/login (:settings env)) page (get-in state [:view :oauth-sessions])
          second-page (get-in (browser-test/act (:settings env) state "oauth/list" {"cursor" (:cursor page)}) [:result :oauth-sessions])]
      (is (= sessions/page-size (count (:items page))))
      (is (= 5 (count (:items second-page))))
      (is (false? (:first-page second-page)))
      (is (nil? (:cursor second-page)))
      (is (= 25 (count (set (map :id (concat (:items page) (:items second-page)))))))
      (is (= "InvalidRequest" (owner/error #(browser-test/act (:settings env) state "oauth/list" {"cursor" "invalid"}))))
      (owner/mutate "UPDATE oauth_sessions SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day' WHERE session_id = ?" id)
      (is (= 24 (db/transact! fixture/*ds* (fn [conn]
                                           (let [a (sessions/list! conn owner/did nil) b (sessions/list! conn owner/did (:cursor a))]
                                             (+ (count (:items a)) (count (:items b)))))))))))

(deftest browser-management-requires-origin-csrf-and-complete-factor
  (let [env (token/env) issued (issue env) browser (web-test/client (:settings env) (:resolver env))
        anonymous (web-test/session browser)
        logged-in (web-test/request browser :security :post "/account/action/login/password" (get anonymous "csrf") owner/credentials)
        csrf (get-in logged-in [:json "csrf"]) id (:session-id (token/access issued))]
    (is (= 403 (:status (web-test/request browser :security :post "/account/action/oauth/revoke" csrf {"id" id}
                                         {:headers {"origin" "https://elsewhere.example.com" "content-type" "application/json"}}))))
    (is (= 403 (:status (web-test/request browser :security :post "/account/action/oauth/revoke" (crypto/token) {"id" id}))))
    (totp/enroll (:settings env))
    (let [anonymous (web-test/session browser)
          pending (web-test/request browser :security :post "/account/action/login/password" (get anonymous "csrf") owner/credentials)]
      (is (= "factor" (get-in pending [:json "stage"])))
      (is (nil? (get-in pending [:json "oauth-sessions"])))
      (is (= 401 (:status (web-test/request browser :security :post "/account/action/oauth/list" (get-in pending [:json "csrf"]) {})))))))

(deftest revocation-http-contract-and-nonce-retry
  (let [env (token/env) issued (issue env) handler (tokens/revocation-handler fixture/*ds* (:settings env) (:resolver env))
        server (http/start! {:host "127.0.0.1" :port 0} handler)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [headers {"Content-Type" "application/x-www-form-urlencoded"}
              params (merge (token/credentials env) {"token" (:access_token issued) "token_type_hint" "refresh_token"})
              invoke (fn [proof] (wire/call client (:port server) "POST" "/oauth/revoke" nil (assoc headers "DPoP" proof) (par-test/form params)))
              missing (invoke (proof/sign (:key env) (assoc (proof/claims) "htu" (str (get-in env [:settings :public-url]) "/oauth/revoke") "nonce" nil)))
              result (invoke (revoke-proof env))]
          (is (= 400 (:status missing)))
          (is (= "use_dpop_nonce" (get-in missing [:body "error"])))
          (is (= 200 (:status result)))
          (is (= 0 (alength ^bytes (:raw result))))
          (is (= "no-store" (get-in result [:headers "cache-control"])))
          (is (= "*" (get-in result [:headers "access-control-allow-origin"])))
          (is (string? (get-in result [:headers "dpop-nonce"])))
          (is (= 405 (:status (wire/call client (:port server) "GET" "/oauth/revoke" nil {} nil))))))
      (finally ((:stop! server))))))
