(ns pds.oauth-tokens-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-par-test :as par-test]
            [pds.oauth.client :as client]
            [pds.oauth.client-auth-test :as auth]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof]
            [pds.oauth.interaction :as interaction]
            [pds.oauth.par :as par]
            [pds.oauth.par-test :as parameters]
            [pds.oauth.tokens :as tokens]
            [pds.proxy-api-test :as wire]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)
(defn env
  ([] (env false))
  ([confidential?]
   (let [client-key (when confidential? (auth/new-key))
         doc (atom (if client-key (auth/document [client-key]) (metadata/metadata)))
         settings (merge (api/settings) proof/settings)]
     (owner/account! settings)
     {:settings settings :key (crypto/keypair) :client-key client-key :document doc
      :resolver (client/resolver {:oauth-client-fetch (fn [_ _] (metadata/response @doc))})})))
(defn credentials [{:keys [client-key]}]
  (if client-key (auth/params client-key) {"client_id" metadata/client-id}))
(defn approved [{:keys [settings key resolver] :as env}]
  (let [verifier (crypto/token) params (merge (parameters/params) (credentials env) {"code_challenge" (crypto/digest-token verifier)})
        pushed (par/push! fixture/*ds* resolver settings params (par-test/dpop-proof key))
        started (interaction/start! fixture/*ds* metadata/client-id (:request_uri pushed))
        view (interaction/inspect! fixture/*ds* (:id started) (:browser started))
        logged-in (interaction/authenticate! fixture/*ds* settings (:id started) (:browser started) (:csrf view) owner/credentials)
        result (interaction/decide! fixture/*ds* resolver settings (:id started) (:browser started) (:csrf logged-in) true)]
    {"grant_type" "authorization_code" "code" (get (owner/fields result) "code") "code_verifier" verifier "redirect_uri" metadata/redirect}))
(defn issue [{:keys [settings key resolver] :as env} params]
  (tokens/issue! fixture/*ds* resolver settings (merge (credentials env) params) (proof/sign key (proof/claims))))
(defn refresh-params [response] {"grant_type" "refresh_token" "refresh_token" (:refresh_token response)})
(defn access [response] (db/transact! fixture/*ds* #(tokens/access-grant! % (:access_token response))))
(defn query [sql & args] (with-open [conn (db/connection fixture/*ds*)] (apply db/query conn sql args)))

(deftest code-exchange-refresh-scope-and-hashed-storage
  (let [env (env) code (approved env) issued (issue env code) grant (access issued)
        refreshed (issue env (assoc (refresh-params issued) "scope" "atproto"))]
    (is (= owner/did (:sub issued) (:did grant)))
    (is (= "DPoP" (:token_type issued)))
    (is (= 300 (:expires_in issued)))
    (is (= "atproto transition:generic" (:scope issued) (:scope grant)))
    (is (= metadata/client-id (:client-id grant)))
    (is (= "atproto" (:scope refreshed) (:scope (access refreshed))))
    (is (not= (:refresh_token issued) (:refresh_token refreshed)))
    (is (not= (:access_token issued) (:access_token refreshed)))
    (is (= (:session-id grant) (:session-id (access refreshed))))
    (is (= grant (access issued)))
    (is (= "atproto transition:generic" (:scope (issue env (refresh-params refreshed)))))
    (is (= 1 (par-test/scalar "SELECT count(*) AS n FROM oauth_sessions")))
    (is (= 6 (par-test/scalar "SELECT count(*) AS n FROM oauth_tokens")))
    (is (every? #(= 43 (count (:token_hash %))) (query "SELECT * FROM oauth_tokens")))
    (is (not-any? #{(:access_token issued) (:refresh_token issued)} (map :token_hash (query "SELECT * FROM oauth_tokens"))))
    (is (= tokens/public-lifetime (long (par-test/scalar "SELECT extract(epoch FROM expires_at - created_at) AS n FROM oauth_sessions"))))))

(deftest wrong-bindings-do-not-consume-or-revoke-the-grant
  (let [env (env) code (approved env)]
    (doseq [bad [(assoc code "code_verifier" (crypto/token)) (dissoc code "code_verifier")
                 (assoc code "redirect_uri" (str metadata/redirect "&extra=1")) (dissoc code "redirect_uri")
                 (assoc code "client_id" "http://localhost")]]
      (is (= "invalid_grant" (proof/error #(issue env bad)))))
    (is (= "invalid_dpop_proof" (proof/error #(issue (assoc env :key (crypto/keypair)) code))))
    (let [issued (issue env code)]
      (is (= "invalid_scope" (proof/error #(issue env (assoc (refresh-params issued) "scope" "atproto transition:email")))))
      (is (= "invalid_dpop_proof" (proof/error #(issue (assoc env :key (crypto/keypair)) (refresh-params issued)))))
      (is (= "invalid_grant" (proof/error #(issue env (assoc code "code_verifier" (crypto/token))))))
      (is (= owner/did (:did (access issued))))
      (is (string? (:access_token (issue env (refresh-params issued))))))))

(deftest code-and-refresh-replay-revoke-the-entire-family
  (let [env (env) code (approved env) issued (issue env code)]
    (is (= "invalid_grant" (proof/error #(issue env code))))
    (is (= "invalid_grant" (proof/error #(access issued))))
    (is (= "invalid_grant" (proof/error #(issue env (refresh-params issued)))))
    (let [issued (issue env (approved env)) rotated (issue env (refresh-params issued))]
      (is (= "invalid_grant" (proof/error #(issue env (refresh-params issued)))))
      (is (= "invalid_grant" (proof/error #(access rotated))))
      (is (= "invalid_grant" (proof/error #(issue env (refresh-params rotated)))))
      (is (= #{"code_replay" "refresh_replay"} (set (map :revoke_reason (query "SELECT * FROM oauth_sessions"))))))))

(deftest expiry-and-account-security-version
  (let [env (env) code (approved env)]
    (with-redefs [dpop/now (constantly (+ (dpop/now) 61))]
      (is (= "invalid_grant" (proof/error #(issue env code)))))
    (let [issued (issue env (approved env))]
      (with-redefs [dpop/now (constantly (+ (dpop/now) 301))]
        (is (= "invalid_grant" (proof/error #(access issued))))
        (is (map? (issue env (refresh-params issued)))))
      (owner/mutate "UPDATE accounts SET status = 'deactivated'")
      (owner/mutate "UPDATE accounts SET status = 'active'")
      (is (= "invalid_grant" (proof/error #(access issued))))
      (is (= "invalid_grant" (proof/error #(issue env (refresh-params issued)))))
      (is (= "account_changed" (:revoke_reason (first (query "SELECT * FROM oauth_sessions"))))))
    (let [issued (issue env (approved env))]
      (with-redefs [dpop/now (constantly (+ (dpop/now) tokens/public-lifetime))]
        (is (= "invalid_grant" (proof/error #(issue env (refresh-params issued)))))))))

(deftest confidential-key-pinning-and-persistent-removal
  (let [env (env true) issued (issue env (approved env)) original @(:document env) another (auth/new-key)]
    (is (= tokens/confidential-lifetime (long (par-test/scalar "SELECT extract(epoch FROM expires_at - created_at) AS n FROM oauth_sessions"))))
    (reset! (:document env) (auth/document [(:client-key env) another]))
    (is (= "invalid_client" (proof/error #(issue (assoc env :client-key another) (refresh-params issued)))))
    (is (map? (access issued)))
    (reset! (:document env) (auth/document [another]))
    (is (= "invalid_client" (proof/error #(issue (assoc env :client-key another) (refresh-params issued)))))
    (reset! (:document env) original)
    (is (= "invalid_grant" (proof/error #(access issued))))
    (is (= "invalid_grant" (proof/error #(issue env (refresh-params issued)))))
    (is (= "client_key_removed" (:revoke_reason (first (query "SELECT * FROM oauth_sessions")))))))

(deftest concurrency-admits-one-rotation-and-revokes-on-reuse
  (let [env (env) issued (issue env (approved env)) gate (promise)
        tasks (mapv (fn [_] (future @gate (try (issue env (refresh-params issued)) (catch clojure.lang.ExceptionInfo e (:oauth-error (ex-data e)))))) (range 8))]
    (deliver gate true)
    (let [results (mapv #(deref % 15000 :timeout) tasks) success (first (filter map? results))]
      (is (= 1 (count (filter map? results))))
      (is (= 7 (count (filter #{"invalid_grant"} results))))
      (is (= "invalid_grant" (proof/error #(access success))))
      (is (= 4 (par-test/scalar "SELECT count(*) AS n FROM oauth_tokens"))))))

(deftest issuance-failure-rolls-back-grant-but-not-proof-use
  (let [{:keys [settings resolver key] :as env} (env) code (approved env)
        params (merge (credentials env) code) dpop-proof (proof/sign key (proof/claims))]
    (owner/mutate "ALTER TABLE oauth_tokens ADD CONSTRAINT test_mint_failure CHECK (kind <> 'access')")
    (is (thrown? java.sql.SQLException (tokens/issue! fixture/*ds* resolver settings params dpop-proof)))
    (is (= 0 (par-test/scalar "SELECT count(*) AS n FROM oauth_sessions")))
    (is (= 0 (par-test/scalar "SELECT count(*) AS n FROM oauth_codes WHERE used_at IS NOT NULL")))
    (owner/mutate "ALTER TABLE oauth_tokens DROP CONSTRAINT test_mint_failure")
    (is (= "invalid_dpop_proof" (proof/error #(tokens/issue! fixture/*ds* resolver settings params dpop-proof))))
    (let [issued (issue env code)]
      (owner/mutate "ALTER TABLE oauth_tokens ADD CONSTRAINT test_mint_failure CHECK (kind <> 'access') NOT VALID")
      (is (thrown? java.sql.SQLException (issue env (refresh-params issued))))
      (is (= 0 (par-test/scalar "SELECT count(*) AS n FROM oauth_tokens WHERE used_at IS NOT NULL")))
      (owner/mutate "ALTER TABLE oauth_tokens DROP CONSTRAINT test_mint_failure")
      (is (map? (issue env (refresh-params issued)))))))

(deftest access-only-clients-and-http-token-contract
  (let [{:keys [settings resolver key document] :as env} (env)]
    (swap! document assoc "grant_types" ["authorization_code"])
    (let [code (approved env) server (http/start! {:port 0 :host "127.0.0.1"} (tokens/handler fixture/*ds* settings resolver))]
      (try
        (with-open [client (HttpClient/newHttpClient)]
          (let [call (fn [headers body] (wire/call client (:port server) "POST" "/oauth/token" nil headers body))
                params (merge (credentials env) code)
                headers {"Content-Type" "application/x-www-form-urlencoded"}
                challenge (call (assoc headers "DPoP" (proof/sign key (dissoc (proof/claims) "nonce"))) (par-test/form params))
                accepted (call (assoc headers "DPoP" (proof/sign key (assoc (proof/claims) "nonce" (get-in challenge [:headers "dpop-nonce"])))) (par-test/form params))]
            (is (= "use_dpop_nonce" (get-in challenge [:body "error"])))
            (is (= 200 (:status accepted)))
            (is (= "DPoP" (get-in accepted [:body "token_type"])))
            (is (= owner/did (get-in accepted [:body "sub"])))
            (is (= "atproto transition:generic" (get-in accepted [:body "scope"])))
            (is (nil? (get-in accepted [:body "refresh_token"])))
            (is (= "no-store" (get-in accepted [:headers "cache-control"])))
            (is (= "*" (get-in accepted [:headers "access-control-allow-origin"])))
            (is (= "unsupported_grant_type" (get-in (call headers (par-test/form {"grant_type" "password"})) [:body "error"])))
            (is (= 300 (long (par-test/scalar "SELECT extract(epoch FROM expires_at - created_at) AS n FROM oauth_sessions"))))))
        (finally ((:stop! server)))))))

(deftest concurrent-code-exchange-and-removed-refresh-grants
  (let [env (env) code (approved env) gate (promise)
        tasks (mapv (fn [_] (future @gate (try (issue env code) (catch clojure.lang.ExceptionInfo e (:oauth-error (ex-data e)))))) (range 6))]
    (deliver gate true)
    (let [results (mapv #(deref % 15000 :timeout) tasks)]
      (is (= 1 (count (filter map? results))))
      (is (= 5 (count (filter #{"invalid_grant"} results))))
      (is (= 1 (par-test/scalar "SELECT count(*) AS n FROM oauth_sessions")))
      (is (= "invalid_grant" (proof/error #(access (first (filter map? results)))))))
    (let [issued (issue env (approved env))]
      (swap! (:document env) assoc "grant_types" ["authorization_code"])
      (is (= "invalid_grant" (proof/error #(issue env (refresh-params issued)))))
      (swap! (:document env) assoc "grant_types" ["authorization_code" "refresh_token"])
      (is (= "invalid_grant" (proof/error #(access issued)))))))
