(ns pds.oauth-include-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.lexicon-resolver-test :as publisher]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-par-test :as par-test]
            [pds.oauth-permissions-test :as direct]
            [pds.oauth-resource-test :as resource]
            [pds.oauth-tokens-test :as token]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof]
            [pds.oauth.interaction :as interaction]
            [pds.oauth.par :as par]
            [pds.oauth.par-test :as params]
            [pds.oauth.permission-cache :as cache]
            [pds.oauth.sessions :as sessions]
            [pds.proxy-test :as upstream]
            [pds.record-proof-test :as proofs]
            [pds.repository-test :as repository])
  (:import [java.io InputStream]))

(use-fixtures :each fixture/isolated-database)
(def include (str "include:" publisher/nsid))
(defn install! [publisher permissions]
  (let [schema (assoc-in publisher/schema ["defs" "main"]
                         {"type" "permission-set" "title" "Post and connect" "title:lang" {"fr" "Publier et échanger"}
                          "detail" "Share your posts." "permissions" permissions})]
    (reset! (:response publisher) {:status 200 :body (proofs/slice (repository/fixture (:key publisher) {publisher/path schema}) publisher/path)})))
(defn repo [action] {"type" "permission" "resource" "repo" "collection" ["com.example.note"] "action" [action]})
(defn env []
  (let [env (token/env) publisher (publisher/fixture) clock (atom (dpop/now))
        cache (assoc (cache/cache fixture/*ds* (:resolver publisher)) :clock #(deref clock))]
    (install! publisher [(repo "create")])
    (assoc env :publisher publisher :clock clock :cache cache :settings (assoc (:settings env) :oauth-permission-cache cache))))
(defn declare! [env scope] (swap! (:document env) assoc "scope" scope))
(defn approval [env scope]
  (declare! env scope)
  (let [verifier (crypto/token)
        parameters (assoc (params/params) "scope" scope "code_challenge" (crypto/digest-token verifier))
        pushed (par/push! fixture/*ds* (:resolver env) (:settings env) parameters (par-test/dpop-proof (:key env)))
        flow (interaction/start! fixture/*ds* metadata/client-id (:request_uri pushed))
        view (interaction/inspect! fixture/*ds* (:id flow) (:browser flow))
        consent (interaction/authenticate! fixture/*ds* (:settings env) (:id flow) (:browser flow) (:csrf view) owner/credentials)
        decision (interaction/decide! fixture/*ds* (:resolver env) (:settings env) (:id flow) (:browser flow) (:csrf consent) true)]
    {:view consent :code {"grant_type" "authorization_code" "code" (get (owner/fields decision) "code")
                         "code_verifier" verifier "redirect_uri" metadata/redirect}}))
(defn owner-sessions []
  (db/transact! fixture/*ds* #(do (db/query % "SELECT did FROM accounts WHERE did = ? FOR UPDATE" owner/did)
                                 (:items (sessions/list! % owner/did nil)))))

(deftest consent-and-initial-access-retain-the-approved-schema
  (let [{:keys [publisher clock cache] :as env} (env) scope (str "atproto " include)
        {:keys [view code]} (approval env scope)
        _ (install! publisher [(repo "delete")])
        _ (swap! clock + cache/stale-seconds)
        _ (cache/resolve! cache publisher/nsid)
        calls (count @(:calls publisher))
        issued (token/issue env code) old-grant (token/access issued)
        handler (app/handler (resource/settings env) fixture/*ds*)]
    (is (= ["Confirm your account identity."] (:permissions view)))
    (is (= "Post and connect" (get-in view [:permission-sets 0 :title])))
    (is (= {"fr" "Publier et échanger"} (get-in view [:permission-sets 0 :title:lang])))
    (is (= ["Create public records in com.example.note."] (get-in view [:permission-sets 0 :permissions])))
    (is (= scope (:scope issued)))
    (is (= 200 (:status (direct/call! handler env issued "createRecord" (direct/note "old")))))
    (is (= 403 (:status (direct/call! handler env issued "deleteRecord" (direct/note "old")))))
    (is (= calls (count @(:calls publisher))) "Exchange and resource requests never re-resolve consent")
    (let [fresh (token/issue env (token/refresh-params issued))]
      (is (= 403 (:status (direct/call! handler env fresh "createRecord" (direct/note "new")))))
      (is (= 200 (:status (direct/call! handler env fresh "deleteRecord" (direct/note "old")))))
      (is (= old-grant (token/access issued)))
      (is (= 200 (:status (direct/call! handler env issued "createRecord" (direct/note "still-old")))))
      (is (= ["Delete public records in com.example.note."] (get-in (first (owner-sessions)) [:permission-sets 0 :permissions])))
      (let [narrow (token/issue env (assoc (token/refresh-params fresh) "scope" "atproto"))]
        (is (= ["atproto"] (:permissions (token/access narrow))))
        (is (= 403 (:status (direct/call! handler env narrow "deleteRecord" (direct/note "still-old")))))
        (is (= "invalid_scope" (proof/error #(token/issue env (assoc (token/refresh-params narrow) "scope" "atproto repo:*")))))
        (is (= scope (:scope (token/issue env (token/refresh-params narrow)))))))))

(deftest resolution-failure-rejects-new-authorizations-but-preserves-existing-refresh
  (let [{:keys [publisher clock] :as env} (env) scope (str "atproto " include)
        issued (direct/mint! env scope)]
    (reset! (:response publisher) {:status 503 :body (byte-array 0)})
    (swap! clock + cache/expiry-seconds)
    (is (= "temporarily_unavailable" (proof/error #(token/approved env {"scope" scope}))))
    (is (= 1 (:n (first (token/query "SELECT count(*) AS n FROM oauth_sessions")))))
    (db/transact! fixture/*ds* #(db/execute! % "DELETE FROM oauth_permission_set_cache"))
    (let [refreshed (token/issue env (token/refresh-params issued))
          handler (app/handler (resource/settings env) fixture/*ds*)]
      (is (= 200 (:status (direct/call! handler env refreshed "createRecord" (direct/note "fallback")))))
      (is (= "invalid_grant" (proof/error #(token/issue env (token/refresh-params issued)))))
      (is (= [{:revoke_reason "refresh_replay"}] (token/query "SELECT revoke_reason FROM oauth_sessions"))))))

(deftest failure-and-revocation-during-refresh-do-not-consume-a-valid-token
  (let [env (env) issued (direct/mint! env (str "atproto " include))
        failing (assoc-in env [:settings :oauth-permission-cache :resolve] (fn [_] (throw (Exception.))))]
    ;; A temporary infrastructure error (distinct from a cached remote outage)
    ;; leaves a still-valid refresh grant available for a later request.
    (with-redefs [cache/resolve! (fn [& _] (throw (ex-info "temporary" {:oauth-error "temporarily_unavailable"})))]
      (is (= "temporarily_unavailable" (proof/error #(token/issue failing (token/refresh-params issued))))))
    (is (nil? (:used_at (first (token/query "SELECT used_at FROM oauth_tokens WHERE kind = 'refresh'")))))
    (let [fresh (token/issue env (token/refresh-params issued))]
      (with-redefs [cache/resolve! (fn [& _]
                                   (db/transact! fixture/*ds* #(db/execute! % "UPDATE oauth_sessions SET revoked_at = now()"))
                                   (throw (ex-info "temporary" {:oauth-error "temporarily_unavailable"})))]
        (is (= "invalid_grant" (proof/error #(token/issue env (token/refresh-params fresh))))))
      (is (nil? (:used_at (first (token/query "SELECT used_at FROM oauth_tokens WHERE token_hash = ?" (crypto/digest-token (:refresh_token fresh))))))))))

(deftest permission-set-authority-and-inherited-rpc-audiences-are-enforced
  (upstream/with-service
    (fn [{:keys [client fetch calls]}]
      (let [{:keys [publisher] :as env} (env)
            inherited {"type" "permission" "resource" "rpc" "lxm" ["com.example.read"] "inheritAud" true}
            _ (install! publisher [(repo "create") inherited
                                   {"type" "permission" "resource" "repo" "collection" ["com.other.record"]}
                                   {"type" "permission" "resource" "blob" "accept" ["*/*"]}
                                   {"type" "permission" "resource" "identity" "attr" "*"}
                                   {"type" "permission" "resource" "account" "attr" "email"}])
            scope (str "atproto " include "?aud=" (str/replace upstream/audience "#" "%23"))
            issued (direct/mint! env scope) lookups (atom 0)
            handler (app/handler (assoc (resource/settings env) :http-client client :proxy-appview-service upstream/audience
                                       :fetch (fn [& args] (swap! lookups inc) (apply fetch args))) fixture/*ds*)]
        (is (= 403 (:status (resource/call handler (resource/request env issued :get "/xrpc/com.example.other")))))
        (is (= 403 (:status (resource/call handler (assoc-in (resource/request env issued :get "/xrpc/com.example.read")
                                                           [:headers "atproto-proxy"] "did:web:other.example.com#appview")))))
        (is (zero? @lookups))
        (is (= 200 (:status (resource/call handler (resource/request env issued :get "/xrpc/com.example.read")))))
        (is (= 1 (count @calls)))
        (is (nil? (get-in (resource/call handler (resource/request env issued :get resource/session-path)) [:json "email"])))
        (is (= 403 (:status (resource/call handler (resource/request env issued :post "/xrpc/com.atproto.identity.updateHandle") {"handle" "other.example.com"}))))
        (let [unread (proxy [InputStream] [] (read [] (throw (AssertionError. "Unauthorized upload body was read"))))]
          (is (= 403 (:status (handler (-> (resource/request env issued :post "/xrpc/com.atproto.repo.uploadBlob")
                                          (assoc-in [:headers "content-type"] "image/png") (assoc :body unread)))))))
        (is (= 403 (:status (direct/call! handler env issued "createRecord"
                                         (-> (direct/note "denied") (assoc "collection" "com.other.record")
                                             (assoc-in ["record" "$type"] "com.other.record"))))))))))

(deftest pre-migration-direct-token-authority-remains-valid
  (let [env (token/env) issued (direct/mint! env "atproto repo:com.example.note?action=create")
        handler (app/handler (resource/settings env) fixture/*ds*)]
    (db/transact! fixture/*ds* #(db/execute! % "UPDATE oauth_tokens SET permissions = NULL WHERE kind = 'access'"))
    (is (= 200 (:status (direct/call! handler env issued "createRecord" (direct/note "legacy")))))
    (is (= 403 (:status (direct/call! handler env issued "deleteRecord" (direct/note "legacy")))))))
