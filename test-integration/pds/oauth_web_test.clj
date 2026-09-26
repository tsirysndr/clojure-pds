(ns pds.oauth-web-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.invites :as invites]
            [pds.oauth-interaction-test :as owner]
            [pds.passkeys-test :as passkeys]
            [pds.oauth-par-test :as par-test]
            [pds.oauth-tokens-test :as token-test]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.interaction :as interaction]
            [pds.oauth.par :as par]
            [pds.oauth.par-test :as params]
            [pds.oauth.web :as web]
            [pds.plc-directory-test :as directory]
            [pds.plc-provision-test :as plc-test]
            [pds.security.browser :as browser]
            [pds.security.web :as security]
            [pds.server-api-test :as api]
            [pds.totp-test :as totp]))

(use-fixtures :each fixture/isolated-database)
(def signup {"handle" "new.example.com" "email" "new@example.com" "password" "signup-password"})
(defn client [settings resolver]
  {:settings settings :cookies (atom {}) :oauth (web/handler fixture/*ds* settings resolver) :security (security/handler fixture/*ds* settings)})
(defn request [client target method path csrf body & [extra]]
  (let [[uri query] (str/split path #"\?" 2)
        response ((get client target)
                  (merge {:request-method method :uri uri :query-string query
                          :headers (cond-> {"cookie" (str/join "; " (map (fn [[k v]] (str k "=" v)) @(:cookies client)))
                                            "origin" (get-in client [:settings :public-url]) "content-type" "application/json"}
                                     csrf (assoc "x-csrf-token" csrf))
                          :body (when body (java.io.ByteArrayInputStream. (.getBytes (json/write-str body) "UTF-8")))} extra))]
    (when-let [cookie (get-in response [:headers "Set-Cookie"])]
      (let [[k v] (str/split (first (str/split cookie #";")) #"=" 2)] (swap! (:cookies client) assoc k v)))
    (assoc response :json (when (str/starts-with? (get-in response [:headers "Content-Type"] "") "application/json")
                           (json/read-str (:body response))))))
(defn start [env client overrides]
  (let [verifier (crypto/token) params (merge (params/params) {"code_challenge" (crypto/digest-token verifier)} overrides)
        pushed (par/push! fixture/*ds* (:resolver env) (:settings env) params (par-test/dpop-proof (:key env)))
        query (String. (par-test/form {"client_id" metadata/client-id "request_uri" (:request_uri pushed)}) "UTF-8")
        response (request client :oauth :get (str "/oauth/authorize?" query) nil nil)]
    (is (= 303 (:status response)))
    {:path (get-in response [:headers "Location"]) :verifier verifier}))
(defn state [client flow] (:json (request client :oauth :get (str (:path flow) "/state") nil nil)))
(defn session [client] (:json (request client :security :get "/account/session" nil nil)))
(defn attach [client flow owner]
  (request client :oauth :post (str (:path flow) "/attach") (get (state client flow) "csrf") {"accountCsrf" (get owner "csrf")}))

(deftest prompt-create-registration-consent-and-token-exchange
  (let [env (token-test/env) client (client (:settings env) (:resolver env)) flow (start env client {"prompt" "create"})
        anonymous (session client) initial (state client flow)
        created (request client :security :post "/account/action/signup" (get anonymous "csrf") signup)]
    (is (= "create" (get-in initial ["parameters" "prompt"])))
    (is (= 200 (:status (request client :oauth :get (:path flow) nil nil))))
    (is (= "authenticated" (get-in created [:json "stage"])))
    (is (nil? (get-in created [:json "accessJwt"])))
    (is (= 1 (par-test/scalar "SELECT count(*) AS n FROM sessions")) "Only the pre-existing test account has a legacy session")
    (let [attached (attach client flow (:json created))
          consent (:json attached)
          decision (request client :oauth :post (str (:path flow) "/decide") (get consent "csrf") {"approve" true})
          fields (owner/fields {:location (get-in decision [:json "location"])})
          tokens (token-test/issue env {"grant_type" "authorization_code" "code" (get fields "code")
                                        "redirect_uri" metadata/redirect "code_verifier" (:verifier flow)})]
      (is (= "did:web:new.example.com" (get consent "did") (:sub tokens)))
      (is (not= (get initial "csrf") (get consent "csrf")))
      (is (= 200 (:status decision)))
      (is (= 400 (:status (request client :oauth :post (str (:path flow) "/decide") (get consent "csrf") {"approve" true})))))))

(deftest browser-bridge-needs-both-csrf-proofs-and-completed-factors
  (let [env (token-test/env) client (client (:settings env) (:resolver env)) flow (start env client {})
        codes (:recovery-codes (totp/enroll (:settings env)))
        anonymous (session client)
        primary (request client :security :post "/account/action/login/password" (get anonymous "csrf") owner/credentials)]
    (is (= "factor" (get-in primary [:json "stage"])))
    (is (= 401 (:status (attach client flow (:json primary)))))
    (let [verified (request client :security :post "/account/action/login/factor" (get-in primary [:json "csrf"]) {"code" (first codes)})]
      (is (= 403 (:status (attach client flow {"csrf" (crypto/token)}))))
      (is (= 400 (:status (request client :oauth :post (str (:path flow) "/attach") (crypto/token) {"accountCsrf" (get-in verified [:json "csrf"])}))))
      (is (= 200 (:status (attach client flow (:json verified)))))
      (owner/mutate "UPDATE accounts SET status = 'deactivated'")
      (is (= "access_denied" (get-in (request client :oauth :post (str (:path flow) "/decide") (get (state client flow) "csrf") {"approve" true}) [:json "error"]))))))

(deftest browser-isolation-origin-denial-and-fresh-login
  (let [env (token-test/env) client (client (:settings env) (:resolver env)) anonymous (session client)
        authenticated (:json (request client :security :post "/account/action/login/password" (get anonymous "csrf") owner/credentials))]
    (with-redefs [dpop/now (constantly (+ 2 (dpop/now)))]
      (let [flow (start env client {"prompt" "login"}) other (assoc client :cookies (atom {}))]
        (is (= "login_required" (get-in (attach client flow authenticated) [:json "error"])))
        (is (= 400 (:status (request other :oauth :get (str (:path flow) "/state") nil nil))))
        (is (= 403 (:status (request client :oauth :post (str (:path flow) "/decide") nil {"approve" false} {:headers {"origin" "https://evil.example"}}))))
        (let [denied (request client :oauth :post (str (:path flow) "/decide") (get (state client flow) "csrf") {"approve" false})]
          (is (= "access_denied" (get (owner/fields {:location (get-in denied [:json "location"])}) "error")))
          (is (= 0 (par-test/scalar "SELECT count(*) AS n FROM oauth_codes"))))))))

(deftest registration-policy-and-browser-preflight
  (let [settings (assoc (api/settings) :invite-required true) anonymous (browser/open! fixture/*ds* nil)
        token (:token anonymous) csrf (get-in anonymous [:view :csrf])]
    (is (= "InvalidCsrf" (owner/error #(browser/register! fixture/*ds* settings token (crypto/token) signup))))
    (is (= 0 (par-test/scalar "SELECT count(*) AS n FROM accounts")))
    (is (= "SignupDisabled" (owner/error #(browser/register! fixture/*ds* (assoc settings :signup-enabled false) token csrf signup))))
    (is (= "InvalidInviteCode" (owner/error #(browser/register! fixture/*ds* settings token csrf signup))))
    (is (= 0 (par-test/scalar "SELECT count(*) AS n FROM accounts")))
    (let [invite (db/transact! fixture/*ds* #(invites/create! % "admin" 1))
          created (browser/register! fixture/*ds* settings token csrf (assoc signup "inviteCode" invite))]
      (is (= "authenticated" (get-in created [:view :stage])))
      (is (= 0 (par-test/scalar "SELECT count(*) AS n FROM sessions")))
      (is (= 1 (par-test/scalar "SELECT count(*) AS n FROM invite_uses")))
      (is (= "BrowserSessionRequired" (owner/error #(browser/register! fixture/*ds* settings token csrf signup)))))))

(deftest browser-registration-supports-plc-without-legacy-tokens
  (directory/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (plc-test/settings client origin) anonymous (browser/open! fixture/*ds* nil)
            registered (browser/register! fixture/*ds* settings (:token anonymous) (get-in anonymous [:view :csrf]) signup)]
        (is (= "authenticated" (get-in registered [:view :stage])))
        (is (= 0 (par-test/scalar "SELECT count(*) AS n FROM sessions")))
        (is (= 1 (par-test/scalar "SELECT count(*) AS n FROM plc_identities WHERE status = 'ready'")))))))

(deftest passkey-owner-can-approve-but-cannot-switch-the-login-hint
  (let [env (token-test/env) client (client (:settings env) (:resolver env))
        credential (passkeys/register (:settings env) (crypto/token))
        flow (start env client {"login_hint" "other.example.com"})
        anonymous (session client)
        started (:json (request client :security :post "/account/action/login/passkey/begin" (get anonymous "csrf") {"identifier" owner/did}))
        response (passkeys/authenticator (get-in started ["result" "options"]) :mode "authenticate" :credential credential)
        signed-in (:json (request client :security :post "/account/action/login/passkey/finish" (get started "csrf")
                                  {"id" (get-in started ["result" "id"]) "response" (json/write-str (get response "response"))}))]
    (is (= "authenticated" (get signed-in "stage")))
    (is (= "access_denied" (get-in (attach client flow signed-in) [:json "error"])))
    (let [matching (start env client {"login_hint" owner/did})]
      (is (= 200 (:status (attach client matching signed-in)))))))

(deftest authorization-start-rejects-unpushed-and-framed-requests
  (let [env (token-test/env) client (client (:settings env) (:resolver env))]
    (doseq [url ["/oauth/authorize" "/oauth/authorize?client_id=https%3A%2F%2Fevil.example&request_uri=bad"
                 "/oauth/authorize?client_id=http%3A%2F%2Flocalhost&request_uri=bad&redirect_uri=https%3A%2F%2Fevil.example"]]
      (let [response (request client :oauth :get url nil nil)]
        (is (= 400 (:status response)))
        (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
        (is (not (str/includes? (:body response) "evil.example")))))
    (let [params (params/params) pushed (par/push! fixture/*ds* (:resolver env) (:settings env) params (par-test/dpop-proof (:key env)))
          query (String. (par-test/form {"client_id" metadata/client-id "request_uri" (:request_uri pushed)}) "UTF-8")
          url (str "/oauth/authorize?" query)]
      (is (= 400 (:status (request client :oauth :get url nil nil {:headers {"sec-fetch-mode" "navigate" "sec-fetch-dest" "iframe"}}))))
      (is (= 303 (:status (request client :oauth :get url nil nil {:headers {"sec-fetch-mode" "navigate" "sec-fetch-dest" "document"}})))))))
