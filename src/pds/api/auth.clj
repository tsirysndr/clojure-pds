(ns pds.api.auth
  "`social.rocksky.auth.*`: two-factor and passkeys as XRPC methods.

  The atproto lexicon defines neither, so each PDS grew its own browser
  interface and no single client could drive all of them. These routes put the
  existing factor and passkey machinery behind one bearer-authorised contract,
  which also means the gateway routes them to the account's own PDS with no
  special handling.

  A password is required to add or remove any way of signing in. An access token
  proves the session, not the owner, and a stolen session must not be able to
  take the second factor off or register a credential of its own."
  (:require [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.api.server :as server]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.request :as request]
            [pds.security.factors :as factors]
            [pds.security.passkeys :as passkeys]))

(defn- full-session!
  "App passwords must not reach account security. They are issued to clients and
  are not the owner proving who they are."
  [account]
  (when (:app_password_id account)
    (errors/raise! 403 "Forbidden" "Account security requires a full session, not an app password"))
  account)

(defn- password! [account supplied]
  (when-not (and (string? supplied)
                 (crypto/password-matches? supplied (:password_hash account)))
    (errors/raise! 401 "InvalidCredentials" "Incorrect password"))
  account)

(defn- raise-result!
  "The factor functions return invalid proofs rather than throwing, so attempt
  accounting commits. Turn one into a response once that is done."
  [result]
  (when-let [error (:error result)]
    (errors/raise! (or (:status result) 400) error "Invalid two-factor code"))
  result)

;; A ceremony is claimed with both halves: the challenge handle and the secret
;; the server bound to it. There is no cookie to hold the secret, so it travels
;; inside the opaque requestId and the pair is what authorises the claim.
(defn- request-id [{:keys [id browser]}] (str id "." browser))

(defn- credential-options
  "The WebAuthn options themselves.

  `toCredentialsCreateJson` and `toCredentialsGetJson` already wrap their result
  in `publicKey`, ready to hand to `navigator.credentials`. The contract carries
  the options alone, so wrapping again would nest it twice and a client reading
  `publicKey.challenge` would find nothing."
  [options]
  (or (get options "publicKey") options))

(defn- split-request-id [value]
  (let [[id browser] (when (string? value) (str/split value #"\." 2))]
    ;; An empty half is not a missing half in Clojure, so check for content:
    ;; neither the handle nor the secret may be blank.
    (when-not (and (not (str/blank? id)) (not (str/blank? browser)))
      (errors/raise! 400 "RequestExpired" "Unknown passkey request"))
    [id browser]))

(defn- factor-proof!
  "Supplies the two-factor code when the account has a factor, so callers do not
  have to ask whether one is enabled."
  [conn settings did code]
  (when (factors/enabled? conn did)
    (raise-result! (factors/verify! conn settings did code))))

(defn- passkey-view [credential]
  (cond-> {"id" (:id credential)}
    (:name credential) (assoc "name" (:name credential))
    (:created-at credential) (assoc "createdAt" (:created-at credential))
    (:last-used-at credential) (assoc "lastUsedAt" (:last-used-at credential))))

(defn- account-for!
  "The account a sign-in names. A passkey challenge is offered per account here,
  so the identifier is required rather than optional."
  [conn identifier]
  (when-not (string? identifier)
    (errors/raise! 400 "InvalidRequest" "identifier is required to sign in with a passkey"))
  (or (first (db/query conn "SELECT * FROM accounts WHERE did = ? OR handle = ?"
                       (str/lower-case identifier) (str/lower-case identifier)))
      ;; Saying the account is unknown discloses nothing: in atproto a handle is
      ;; public, and resolveHandle already answers that for anyone who asks.
      (errors/raise! 401 "AccountNotFound" "No passkey is registered for that account")))

(defn- second-factor!
  "A passkey replaces the password, not a factor on top of it."
  [conn settings did body]
  (when (factors/enabled? conn did)
    (let [supplied (or (get body "totpCode") (get body "authFactorToken"))]
      (when-not supplied
        (errors/raise! 401 "AuthFactorTokenRequired" "A two-factor code is required"))
      (raise-result! (factors/verify! conn settings did supplied)))))

(defn routes [ds settings]
  (let [authed (fn [options f] (server/authenticated ds settings options f))
        body (fn [r] (or (request/json-body r) {}))]
    {"/xrpc/social.rocksky.auth.getTwoFactor"
     (server/json-route :get
       (authed {} (fn [conn account r]
                    (request/query-params r)
                    (let [status (factors/status conn (:did account))]
                      {"state" (:state status)
                       "recoveryRemaining" (:recovery-remaining status)}))))

     "/xrpc/social.rocksky.auth.beginTwoFactor"
     (server/json-route :post
       (authed {} (fn [conn account r]
                    (let [p (body r)]
                      (password! (full-session! account) (get p "password"))
                      (let [enrollment (factors/begin! conn settings (:did account))]
                        {"state" "pending"
                         "secret" (:secret enrollment)
                         "uri" (:uri enrollment)})))))

     "/xrpc/social.rocksky.auth.confirmTwoFactor"
     (server/json-route :post
       (authed {} (fn [conn account r]
                    (full-session! account)
                    (let [result (raise-result!
                                   (factors/confirm! conn settings (:did account)
                                                     (get (body r) "code")))]
                      {"state" "enabled"
                       "recoveryCodes" (:recovery-codes result)}))))

     "/xrpc/social.rocksky.auth.disableTwoFactor"
     (server/json-route :post
       (authed {} (fn [conn account r]
                    (let [p (body r)]
                      (password! (full-session! account) (get p "password"))
                      (raise-result! (factors/disable! conn settings (:did account) (get p "code")))
                      {"state" "disabled"}))))

     "/xrpc/social.rocksky.auth.regenerateRecoveryCodes"
     (server/json-route :post
       (authed {} (fn [conn account r]
                    (let [p (body r)]
                      (password! (full-session! account) (get p "password"))
                      (let [result (raise-result!
                                     (factors/regenerate! conn settings (:did account) (get p "code")))]
                        {"recoveryCodes" (:recovery-codes result)})))))

     "/xrpc/social.rocksky.auth.listPasskeys"
     (server/json-route :get
       (authed {} (fn [conn account r]
                    (request/query-params r)
                    {"passkeys" (mapv passkey-view (passkeys/list-credentials conn (:did account)))})))

     "/xrpc/social.rocksky.auth.beginPasskeyRegistration"
     (server/json-route :post
       (authed {} (fn [conn account r]
                    (let [p (body r)
                          did (:did account)]
                      (password! (full-session! account) (get p "password"))
                      (factor-proof! conn settings did (get p "code"))
                      ;; The server picks the secret and returns it inside the
                      ;; opaque requestId; begin-registration! only stores its
                      ;; digest, so it cannot hand it back later.
                      (let [browser (crypto/token)
                            ceremony (passkeys/begin-registration!
                                       conn settings did browser
                                       (or (get p "name") "passkey"))]
                        {"requestId" (request-id {:id (:id ceremony) :browser browser})
                         "publicKey" (credential-options (:options ceremony))})))))

     "/xrpc/social.rocksky.auth.finishPasskeyRegistration"
     (server/json-route :post
       (authed {} (fn [conn account r]
                    (full-session! account)
                    (let [p (body r)
                          [id browser] (split-request-id (get p "requestId"))
                          result (passkeys/finish-registration! conn settings id browser
                                                               (get p "credential"))]
                      (when-let [error (:error result)]
                        (errors/raise! (or (:status result) 401) error "Passkey was not accepted"))
                      {"passkey" (passkey-view {:id (:credential-id result)
                                                :name (:label result)})}))))

     ;; Signing in with a passkey. Unauthenticated: this is how a session begins.
     "/xrpc/social.rocksky.auth.beginPasskeyLogin"
     (server/json-route :post
       (fn [r]
         (db/transact! ds
           (fn [conn]
             (let [body (or (request/json-body r) {})
                   account (account-for! conn (get body "identifier"))
                   browser (crypto/token)
                   ceremony (passkeys/begin-authentication! conn settings (:did account) browser)]
               {"requestId" (request-id {:id (:id ceremony) :browser browser})
                "publicKey" (credential-options (:options ceremony))})))))

     "/xrpc/social.rocksky.auth.finishPasskeyLogin"
     (server/json-route :post
       (fn [r]
         (db/transact! ds
           (fn [conn]
             (let [body (or (request/json-body r) {})
                   [id browser] (split-request-id (get body "requestId"))
                   result (passkeys/finish-authentication! conn settings id browser
                                                           (get body "credential"))]
               (when-let [error (:error result)]
                 (errors/raise! (or (:status result) 401) error "That passkey was not accepted"))
               (let [did (:did result)
                     account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" did))]
                 (second-factor! conn settings did body)
                 (merge (accounts/public-account account)
                        (auth/issue! conn settings did nil))))))))

     "/xrpc/social.rocksky.auth.deletePasskey"
     (server/empty-route
       (authed {} (fn [conn account r]
                    (let [p (body r)]
                      (password! (full-session! account) (get p "password"))
                      (passkeys/remove! conn (:did account) (get p "id"))))))}))
