(ns pds.oauth-resource-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.api.server :as server-api]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-tokens-test :as token]
            [pds.oauth.client-auth-test :as client-auth]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof]
            [pds.oauth.resource :as resource]
            [pds.proxy-api-test :as wire]
            [pds.proxy-test :as upstream]
            [pds.response-body :as body]
            [pds.service-auth-test :as jwt]
            [pds.xrpc :as xrpc])
  (:import [java.io ByteArrayInputStream]
           [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)
(def session-path "/xrpc/com.atproto.server.getSession")
(def write-path "/xrpc/com.atproto.repo.createRecord")
(def record-body {"repo" owner/did "collection" "com.example.note" "rkey" "first" "validate" false
                  "record" {"$type" "com.example.note" "text" "Hello from OAuth"}})
(defn settings [env]
  (assoc (:settings env) :oauth-client-fetch (fn [_ _] (metadata/response @(:document env)))))
(defn mint [env scope]
  (swap! (:document env) assoc "scope" "atproto transition:generic transition:email transition:chat.bsky")
  (token/issue env (token/approved env {"scope" scope})))
(defn request
  ([env issued method path] (request env issued method path {}))
  ([env issued method path claim-overrides]
   (let [[uri query] (str/split path #"\?" 2)]
     {:request-method method :uri uri :query-string query :remote-addr "127.0.0.1"
      :headers {"authorization" (str "DPoP " (:access_token issued)) "content-type" "application/json"
                "dpop" (proof/sign (:key env) (merge (proof/claims)
                                                     {"htm" (str/upper-case (name method)) "htu" (str (get-in env [:settings :public-url]) uri)
                                                      "ath" (crypto/digest-token (:access_token issued))} claim-overrides))}})))
(defn call [handler request & [body]]
  (let [response (handler (cond-> request body (assoc :body (ByteArrayInputStream. (.getBytes (json/write-str body) "UTF-8")))))
        response (if (body/stream? (:body response))
                   (with-open [stream (:body response)]
                     (assoc response :body (.readAllBytes ^java.io.InputStream (:input stream))))
                   response)]
    (assoc response :json (try (json/read-str (if (bytes? (:body response))
                                               (String. ^bytes (:body response) "UTF-8") (:body response)))
                              (catch Exception _ nil)))))

(deftest bound-resource-proofs-nonces-and-no-bearer-fallback
  (let [env (token/env) issued (mint env "atproto transition:generic") handler (app/handler (settings env) fixture/*ds*)
        missing (call handler (request env issued :get session-path {"nonce" nil}))
        valid (request env issued :get session-path {"nonce" (get-in missing [:headers "DPoP-Nonce"])})
        success (call handler valid)]
    (is (= 401 (:status missing)))
    (is (= "DPoP error=\"use_dpop_nonce\", resource_metadata=\"https://pds.example.com/.well-known/oauth-protected-resource\"" (get-in missing [:headers "WWW-Authenticate"])))
    (is (= 200 (:status success)))
    (is (= owner/did (get-in success [:json "did"])))
    (is (not-any? #(contains? (:json success) %) ["email" "emailConfirmed" "emailAuthFactor"]))
    (is (= "no-store" (get-in success [:headers "Cache-Control"])))
    (is (= "*" (get-in success [:headers "Access-Control-Allow-Origin"])))
    (is (= "invalid_dpop_proof" (get-in (call handler valid) [:json "error"])))
    (doseq [claims [{"ath" nil} {"ath" (crypto/digest-token "different")} {"htm" "POST"}
                    {"htu" "https://attacker.example/xrpc/com.atproto.server.getSession"}]]
      (is (= 401 (:status (call handler (request env issued :get session-path claims))))))
    (is (= 401 (:status (call handler (request (assoc env :key (crypto/keypair)) issued :get session-path)))))
    (doseq [header [(str "Bearer " (:access_token issued)) (str "DPoP " (:refresh_token issued))
                    (str "DPoP " (:access_token issued) ",DPoP " (:access_token issued))]]
      (is (= 401 (:status (call handler (assoc-in (request env issued :get session-path) [:headers "authorization"] header))))))
    (is (= 401 (:status (call handler (update (request env issued :get session-path) :headers dissoc "dpop")))))
    (is (= 401 (:status (call handler {:uri session-path :request-method :get
                                      :headers {"authorization" (str "Bearer " (:access_token issued))}}))))
    (is (= 200 (:status (call handler (-> (request env issued :get session-path)
                                         (assoc-in [:headers "host"] "attacker.example")
                                         (assoc-in [:headers "x-forwarded-host"] "attacker.example"))))))
    (is (= 401 (:status (call handler (request env issued :get session-path {"htu" "https://attacker.example/xrpc/com.atproto.server.getSession"})))))))

(deftest scope-gates-records-email-and-account-management
  (let [env (token/env) basic (mint env "atproto") generic (mint env "atproto transition:generic")
        email (mint env "atproto transition:email") handler (app/handler (settings env) fixture/*ds*)]
    (is (= 403 (:status (call handler (request env basic :post write-path) record-body))))
    (is (= 403 (:status (call handler (request env email :post write-path) record-body))))
    (is (= 200 (:status (call handler (request env generic :post write-path) record-body))))
    (is (= "alice@example.com" (get-in (call handler (request env email :get session-path)) [:json "email"])))
    (doseq [[method nsid] [[:post "com.atproto.identity.updateHandle"] [:post "com.atproto.server.updateEmail"]
                           [:post "com.atproto.server.deactivateAccount"] [:post "com.atproto.server.createAppPassword"]
                           [:post "com.atproto.repo.importRepo"] [:get "com.atproto.server.getAccountInviteCodes"]]]
      (is (= 403 (:status (call handler (request env generic method (str "/xrpc/" nsid))
                               (if (= nsid "com.atproto.identity.updateHandle") {"handle" "other.example.com"} {})))) nsid))
    (let [legacy (accounts/login! fixture/*ds* (:settings env) owner/credentials)
          req {:uri session-path :request-method :get :headers {"authorization" (str "Bearer " (:accessJwt legacy))}}]
      (is (= "alice@example.com" (get-in (call handler req) [:json "email"]))))))

(deftest endpoint-failure-and-concurrency-never-revive-a-proof
  (let [env (token/env) issued (mint env "atproto transition:generic") handler (app/handler (settings env) fixture/*ds*)
        req (request env issued :post write-path)]
    (is (= 400 (:status (call handler req (dissoc record-body "record")))) )
    (is (= 401 (:status (call handler req record-body))))
    (is (empty? (token/query "SELECT * FROM records")))
    (let [req (request env issued :post write-path) gate (promise)
          jobs (mapv (fn [_] (future @gate (call handler req record-body))) (range 6))]
      (deliver gate true)
      (let [results (mapv #(deref % 15000 {:status :timeout}) jobs)]
        (is (= {200 1 401 5} (frequencies (map :status results)))))
      (is (= 1 (count (token/query "SELECT * FROM records")))))))

(deftest fresh-metadata-key-revocation-and-account-races
  (let [env (token/env true) issued (mint env "atproto transition:generic") original @(:document env)
        handler (app/handler (settings env) fixture/*ds*)]
    (reset! (:document env) (client-auth/document [(client-auth/new-key)]))
    (is (= 401 (:status (call handler (request env issued :get session-path)))))
    (is (= "client_key_removed" (:revoke_reason (first (token/query "SELECT * FROM oauth_sessions")))))
    (reset! (:document env) original)
    (is (= 401 (:status (call handler (request env issued :get session-path)))))
    (let [issued (mint env "atproto")
          offline (app/handler (assoc (settings env) :oauth-client-fetch (fn [_ _] (throw (ex-info "offline secret" {})))) fixture/*ds*)]
      (is (= 503 (:status (call offline (request env issued :get session-path)))))
      (is (= 200 (:status (call handler (request env issued :get session-path)))))
      (let [racing (app/handler (assoc (settings env) :oauth-client-fetch
                                     (fn [_ _] (owner/mutate "UPDATE accounts SET status = 'deactivated'") (metadata/response original))) fixture/*ds*)]
        (is (= 401 (:status (call racing (request env issued :post write-path) record-body))))
        (is (empty? (token/query "SELECT * FROM records")))))))

(deftest service-tokens-and-proxy-enforce-chat-scope-without-forwarding-proof
  (upstream/with-service
    (fn [{:keys [client fetch calls]}]
      (let [env (token/env) generic (mint env "atproto transition:generic") chat (mint env "atproto transition:generic transition:chat.bsky")
            handler (app/handler (assoc (settings env) :http-client client :fetch fetch :proxy-appview-service upstream/audience) fixture/*ds*)
            service-path (str "/xrpc/com.atproto.server.getServiceAuth?aud=" upstream/service-did)]
        (is (= 403 (:status (call handler (request env generic :get service-path)))))
        (is (= 403 (:status (call handler (request env generic :get (str service-path "&lxm=chat.bsky.future.read"))))))
        (is (= 403 (:status (call handler (request env chat :get (str service-path "&lxm=com.atproto.server.createAccount"))))))
        (is (= 400 (:status (call handler (request env chat :get (str service-path "&lxm=com.atproto.identity.updateHandle"))))))
        (let [result (call handler (request env chat :get (str service-path "&lxm=chat.bsky.future.read")))
              claims (:claims (jwt/decode (get-in result [:json "token"])))]
          (is (= 200 (:status result)))
          (is (= "chat.bsky.future.read" (get claims "lxm")))
          (is (= owner/did (get claims "iss"))))
        (is (= 403 (:status (call handler (request env generic :get "/xrpc/chat.bsky.future.read")))))
        (is (empty? @calls))
        (is (= 200 (:status (call handler (assoc-in (request env chat :get "/xrpc/chat.bsky.future.read") [:headers "cookie"] "secret=value")))))
        (let [forwarded (:headers (last @calls))]
          (is (nil? (get forwarded "dpop")))
          (is (nil? (get forwarded "cookie")))
          (is (str/starts-with? (get forwarded "authorization") "Bearer "))
          (is (= "chat.bsky.future.read" (get-in (jwt/decode (subs (get forwarded "authorization") 7)) [:claims "lxm"]))))))))

(deftest actual-http-nonce-challenge-and-cors-preflight
  (let [env (token/env) issued (mint env "atproto") handler (app/handler (settings env) fixture/*ds*)
        server (http/start! {:host "127.0.0.1" :port 0} handler)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [preflight (wire/call client (:port server) "OPTIONS" session-path nil
                                   {"Origin" "https://app.example.com" "Access-Control-Request-Method" "GET"
                                    "Access-Control-Request-Headers" "authorization,dpop"} nil)
              missing (wire/call client (:port server) "GET" session-path nil (:headers (request env issued :get session-path {"nonce" nil})) nil)
              response (wire/call client (:port server) "GET" session-path nil
                                  (:headers (request env issued :get session-path {"nonce" (get-in missing [:headers "dpop-nonce"])})) nil)]
          (is (= 204 (:status preflight)))
          (is (= "*" (get-in preflight [:headers "access-control-allow-origin"])))
          (is (str/includes? (get-in preflight [:headers "access-control-allow-headers"]) "DPoP"))
          (is (= 401 (:status missing)))
          (is (= "DPoP error=\"use_dpop_nonce\", resource_metadata=\"https://pds.example.com/.well-known/oauth-protected-resource\"" (get-in missing [:headers "www-authenticate"])))
          (is (= 200 (:status response)))
          (is (= owner/did (get-in response [:body "did"])))))
      (finally ((:stop! server))))))

(deftest endpoint-transaction-rechecks-expiry-and-revocation-after-proof-acceptance
  (let [env (token/env) config (settings env)
        routed (xrpc/router (server-api/routes fixture/*ds* config))]
    (doseq [change [#(owner/mutate "UPDATE oauth_sessions SET revoked_at = now()")
                   #(owner/mutate "UPDATE oauth_tokens SET created_at = now() - interval '10 minutes', expires_at = now() - interval '1 second' WHERE kind = 'access'")]]
      (let [issued (mint env "atproto")
            handler (resource/wrap (fn [r] (change) (routed r)) fixture/*ds* config (:resolver env))]
        (is (= 401 (:status (call handler (request env issued :get session-path)))))))
    (let [issued (mint env "atproto")
          handler (resource/wrap #(routed (assoc % :request-method :head)) fixture/*ds* config (:resolver env))]
      (is (= 401 (:status (call handler (request env issued :get session-path))))))))

(deftest oauth-blob-upload-and-expired-token-rejection
  (let [env (token/env) generic (mint env "atproto transition:generic") basic (mint env "atproto")
        handler (app/handler (settings env) fixture/*ds*) path "/xrpc/com.atproto.repo.uploadBlob"
        upload (fn [issued] (call handler (-> (request env issued :post path)
                                              (assoc-in [:headers "content-type"] "image/png")
                                              (assoc :body (ByteArrayInputStream. (byte-array [1 2 3]))))))]
    (is (= 403 (:status (upload basic))))
    (is (empty? (token/query "SELECT * FROM blobs")))
    (is (= 200 (:status (upload generic))))
    (is (= owner/did (:did (first (token/query "SELECT * FROM blobs")))))
    (with-redefs [dpop/now (constantly (+ (dpop/now) 301))]
      (is (= 401 (:status (call handler (request env generic :get session-path))))))))
