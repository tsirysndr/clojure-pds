(ns pds.oauth.resource
  "DPoP resource authentication. Proof consumption commits before endpoint work;
  protected handlers recheck the grant in their own account-locked transaction."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.db :as db]
            [pds.oauth.client :as client]
            [pds.oauth.client-auth :as client-auth]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.permissions :as permissions]
            [pds.oauth.proof-store :as proofs]
            [pds.oauth.tokens :as tokens]
            [pds.xrpc :as xrpc]))

(defn dpop? [request]
  (boolean (re-find #"(?i)^DPoP(?:\s|$)" (get-in request [:headers "authorization"] ""))))
(defn- fail! [code]
  (throw (ex-info "Invalid OAuth resource authentication" {:oauth-error code})))
(defn- access! [conn token]
  (try (tokens/access-grant! conn token)
       (catch clojure.lang.ExceptionInfo e
         (if (= "invalid_grant" (:oauth-error (ex-data e))) (fail! "invalid_token") (throw e)))))
(defn- target [request] (select-keys request [:uri :request-method]))
(defn- prepare! [ds settings resolver request]
  (let [[_ token] (re-matches #"(?i)DPoP (at_[A-Za-z0-9_-]{43})" (get-in request [:headers "authorization"] ""))
        _ (when-not token (fail! "invalid_token"))
        grant (db/transact! ds #(access! % token))
        proof (dpop/verify! settings (get-in request [:headers "dpop"])
                            {:method (str/upper-case (name (:request-method request)))
                             :url (str (:public-url settings) (:uri request))
                             :access-token token :jkt (:dpop-jkt grant)})
        ;; Verify the bound proof before fetching client-controlled metadata.
        ;; No database lock is held during HTTPS resolution.
        resolved (client/resolve! resolver (:client-id grant))
        result (db/transact! ds
                 (fn [conn]
                   (let [current (access! conn token)]
                     (when-not (= grant current) (fail! "invalid_token"))
                     (proofs/consume! conn proof)
                     (if (client-auth/binding-current? resolved (:client-binding current))
                       current
                       (do
                         (db/execute! conn "UPDATE oauth_sessions SET revoked_at = now(), revoke_reason = 'client_key_removed'
                                              WHERE session_id = ? AND revoked_at IS NULL" (:session-id current))
                         nil)))))]
    ;; A removed key permanently revokes the family, even if later restored.
    (when-not result (fail! "invalid_token"))
    (assoc request ::verified {:token token :grant result :target (target request)})))

(defn authenticate!
  "Called only by the common account authenticator, inside endpoint transactions.
  The middleware marker cannot be supplied through HTTP headers/body parameters."
  [conn request]
  (let [{:keys [token grant] :as verified} (::verified request)]
    (when-not (and verified (= (:target verified) (target request)))
      (throw (ex-info "DPoP authentication is required" {:xrpc true :status 401 :error "invalid_token"})))
    (let [current (try (access! conn token)
                       (catch clojure.lang.ExceptionInfo e
                         (if (:oauth-error (ex-data e))
                           (throw (ex-info "Invalid or expired OAuth token" {:xrpc true :status 401 :error "invalid_token"}))
                           (throw e))))]
      (when-not (= current grant)
        (throw (ex-info "Invalid OAuth token" {:xrpc true :status 401 :error "invalid_token"})))
      (let [account (assoc (first (db/query conn "SELECT * FROM accounts WHERE did = ?" (:did current)))
                           :oauth-scope (:scope current) :oauth-permissions (:permissions current) :oauth-client-id (:client-id current)
                           :session-id (:session-id current) :access-scope "oauth")]
        (permissions/endpoint! account request)
        account))))

(defn- error-response [e]
  (let [code (:oauth-error (ex-data e))
        code (case code
               ("invalid_token" "invalid_dpop_proof" "use_dpop_nonce") code
               ("invalid_client_metadata" "temporarily_unavailable") "temporarily_unavailable"
               "server_error")
        status (case code "temporarily_unavailable" 503 "server_error" 500 401)]
    (xrpc/error-response status code
                        (case code "use_dpop_nonce" "A current DPoP nonce is required"
                              "temporarily_unavailable" "OAuth authentication is temporarily unavailable"
                              "server_error" "An internal server error occurred"
                              "Invalid OAuth resource authentication"))))
(def cors-headers
  {"Access-Control-Allow-Origin" "*" "Access-Control-Allow-Methods" "GET, HEAD, POST, OPTIONS"
   "Access-Control-Allow-Headers" "Authorization, DPoP, Content-Type, Atproto-Proxy, Atproto-Accept-Labelers, Accept-Language, X-Bsky-Topics"
   "Access-Control-Expose-Headers" "DPoP-Nonce, WWW-Authenticate"})
(defn- challenge [response]
  (let [error (when (#{401 403} (:status response))
                (try (get (json/read-str (:body response)) "error") (catch Exception _ nil)))]
    (cond-> response
      (= 401 (:status response))
      (assoc-in [:headers "WWW-Authenticate"]
                (str "DPoP error=\"" (if (#{"use_dpop_nonce" "invalid_dpop_proof"} error) error "invalid_token") "\""))
      (= "insufficient_scope" error)
      (assoc-in [:headers "WWW-Authenticate"] "DPoP error=\"insufficient_scope\""))))
(defn wrap [handler ds settings resolver]
  (if-not ds handler
   (fn [request]
    ;; Clear internal fields before authentication, including in direct Ring use.
    (let [request (dissoc request ::verified ::permissions/proxy)
          oauth? (or (dpop? request) (contains? (:headers request) "dpop"))]
      (cond
        (not (str/starts-with? (:uri request) "/xrpc/")) (handler request)
        (= :options (:request-method request)) {:status 204 :headers (assoc cors-headers "Cache-Control" "no-store") :body ""}
        (not oauth?) (update (handler request) :headers merge cors-headers)
        :else
        (let [response (try (handler (prepare! ds settings resolver request)) (catch Exception e (error-response e)))]
          (update (challenge response) :headers merge cors-headers
                  {"Cache-Control" "no-store" "Pragma" "no-cache" "DPoP-Nonce" (dpop/nonce settings)})))))))
