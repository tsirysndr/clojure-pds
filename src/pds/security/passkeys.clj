(ns pds.security.passkeys
  "WebAuthn ceremonies backed by PostgreSQL. All functions with ! require a caller
  transaction; management boundaries must require recent owner authentication and
  CSRF. Authentication returns a principal for use in the SAME transaction."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.codec :as codec]
            [pds.request :as request])
  (:import [com.yubico.webauthn AssertionRequest CredentialRepository FinishAssertionOptions FinishRegistrationOptions
            RegisteredCredential RelyingParty StartAssertionOptions StartRegistrationOptions]
           [com.yubico.webauthn.data AttestationConveyancePreference AuthenticatorSelectionCriteria AuthenticatorTransport ByteArray
            PublicKeyCredential PublicKeyCredentialCreationOptions PublicKeyCredentialDescriptor PublicKeyCredentialParameters
            RegistrationExtensionInputs ResidentKeyRequirement RelyingPartyIdentity UserIdentity UserVerificationRequirement]
           [java.net URI]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util Optional]))

(def lifetime 300)
(def max-credentials 20)
(defn now [] (.getEpochSecond (Instant/now)))
(defn- timestamp [] (Instant/ofEpochSecond (now)))
(defn- invalid! [] (errors/raise! 401 "InvalidPasskey" "Invalid or expired passkey ceremony"))
(defn- transaction! [conn]
  (when (.getAutoCommit ^java.sql.Connection conn) (throw (ex-info "Passkey operations require a transaction" {}))))
(defn- account! [conn did]
  (transaction! conn)
  (let [account (first (db/query conn "SELECT * FROM accounts WHERE did = ? FOR UPDATE" did))]
    (when-not (= "active" (:status account)) (invalid!)) account))
(defn- bytes-value [value] (ByteArray. ^bytes value))
(defn- transports [row]
  (set (map #(AuthenticatorTransport/of %) (json/read-str (:transports row)))))
(defn- registered [row]
  (when row
    (-> (RegisteredCredential/builder) (.credentialId (bytes-value (:credential_id row)))
        (.userHandle (bytes-value (:user_handle row))) (.publicKeyCose (bytes-value (:public_key_cose row)))
        (.signatureCount (:signature_count row)) (.backupEligible (:backup_eligible row)) (.backupState (:backed_up row))
        (.transports (transports row)) .build)))
(defn- descriptor [row]
  (-> (PublicKeyCredentialDescriptor/builder) (.id (bytes-value (:credential_id row))) (.transports (transports row)) .build))
(def credential-sql "SELECT p.*, p.transports::text, u.user_handle FROM account_passkeys p JOIN account_webauthn_users u USING (did)")
(defn- repository [conn]
  (reify CredentialRepository
    (getCredentialIdsForUsername [_ did]
      (set (map descriptor (db/query conn "SELECT credential_id, transports::text FROM account_passkeys WHERE did = ?" did))))
    (getUserHandleForUsername [_ did]
      (Optional/ofNullable (some-> (first (db/query conn "SELECT user_handle FROM account_webauthn_users WHERE did = ?" did)) :user_handle bytes-value)))
    (getUsernameForUserHandle [_ handle]
      (Optional/ofNullable (:did (first (db/query conn "SELECT did FROM account_webauthn_users WHERE user_handle = ?" (.getBytes ^ByteArray handle))))))
    (lookup [_ id handle]
      (Optional/ofNullable (registered (first (db/query conn (str credential-sql " WHERE credential_id = ? AND user_handle = ?")
                                                       (.getBytes ^ByteArray id) (.getBytes ^ByteArray handle))))))
    (lookupAll [_ id]
      (set (map registered (db/query conn (str credential-sql " WHERE credential_id = ?") (.getBytes ^ByteArray id)))))))

(defn- relying-party-id
  "The relying party a credential is bound to.

  A credential is bound to its RP ID for life, and a browser only uses one whose
  RP ID equals the page's own domain or is a parent of it. Left at this host, a
  credential registered here can never be used from a shared sign-in page in
  front of several nodes. Configuring the common parent makes one credential
  work from both. A configured value must still be this host or a parent of it:
  anything else would claim credentials for a domain this server does not
  answer for."
  [settings host]
  (if-let [configured (some-> (:webauthn-rp-id settings) str/lower-case not-empty)]
    (if (or (= configured host)
            (and (str/includes? configured ".") (str/ends-with? host (str "." configured))))
      configured
      (throw (ex-info "webauthn-rp-id must be this host or a parent of it"
                      {:rp-id configured :host host})))
    host))

(defn- allowed-origins
  "Origins allowed to run a ceremony, this server's own always among them.

  The page driving the ceremony need not be this node: a console in front of the
  fleet is a different origin, and clientDataJSON carries the page's origin."
  [settings own]
  (into #{own} (keep #(some-> % str/trim not-empty) (:webauthn-origins settings))))

(defn rp-origin [settings]
  (let [url (:public-url settings) uri (try (URI/create url) (catch Exception _ nil))]
    (when-not (and uri (.getHost uri) (nil? (.getUserInfo uri)) (nil? (.getQuery uri)) (nil? (.getFragment uri))
                   (= "" (.getPath uri))
                   (or (= "https" (.getScheme uri)) (and (= "http" (.getScheme uri)) (= "localhost" (.getHost uri)))))
      (throw (ex-info "Passkeys require an HTTPS public origin (localhost HTTP allowed)" {})))
    {:origin url
     :rp-id (relying-party-id settings (.getHost uri))
     :origins (allowed-origins settings url)}))
(defn- relying-party [conn settings]
  (let [{:keys [rp-id origins]} (rp-origin settings)]
    (-> (RelyingParty/builder)
        (.identity (-> (RelyingPartyIdentity/builder) (.id rp-id) (.name "AT Protocol PDS") .build))
        (.credentialRepository (repository conn)) (.origins origins)
        (.allowOriginPort false) (.allowOriginSubdomain false)
        (.attestationConveyancePreference AttestationConveyancePreference/NONE)
        (.preferredPubkeyParams [PublicKeyCredentialParameters/ES256 PublicKeyCredentialParameters/EdDSA PublicKeyCredentialParameters/RS256])
        (.validateSignatureCounter true) .build)))
(defn- browser! [browser]
  (when-not (and (string? browser) (re-matches #"[A-Za-z0-9_-]{43}" browser)) (invalid!)))
(defn- save! [conn account browser ceremony stored options label]
  (browser! browser)
  (let [time (timestamp) id (crypto/token)]
    (db/execute! conn "DELETE FROM webauthn_challenges WHERE challenge_hash IN
                       (SELECT challenge_hash FROM webauthn_challenges WHERE expires_at <= ? ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)" time)
    (when (>= (:n (first (db/query conn "SELECT count(*) AS n FROM webauthn_challenges WHERE did = ? AND used_at IS NULL AND expires_at > ?" (:did account) time))) 8)
      (errors/raise! 429 "TooManyChallenges" "Finish or wait for existing passkey requests to expire"))
    (db/execute! conn "INSERT INTO webauthn_challenges(challenge_hash, browser_hash, did, account_epoch, ceremony, request, label, created_at, expires_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                 (crypto/digest-token id) (crypto/digest-token browser) (:did account) (:oauth_epoch account) ceremony stored label time (.plusSeconds time lifetime))
    {:id id :options (json/read-str options) :expires-in lifetime}))

(defn begin-registration!
  "Call after recent primary or user-verified passkey authentication, including
  any additional account factor. Never expose this as an unauthenticated DID API."
  [conn settings did browser label]
  (let [account (account! conn did)]
    (when-not (and (string? label) (<= 1 (count label) 64) (not (str/blank? label)) (not (re-find #"\p{Cntrl}" label)))
      (errors/invalid! "Passkey name must contain 1 to 64 characters"))
    (when (>= (:n (first (db/query conn "SELECT count(*) AS n FROM account_passkeys WHERE did = ?" did))) max-credentials)
      (errors/raise! 400 "TooManyPasskeys" "Remove an existing passkey before adding another"))
    (db/execute! conn "INSERT INTO account_webauthn_users(did, user_handle) VALUES (?, ?) ON CONFLICT (did) DO NOTHING" did (crypto/random-bytes 32))
    (let [handle (:user_handle (first (db/query conn "SELECT user_handle FROM account_webauthn_users WHERE did = ?" did)))
          options (-> (StartRegistrationOptions/builder)
                      (.user (-> (UserIdentity/builder) (.name did) (.displayName (:handle account)) (.id (bytes-value handle)) .build))
                      (.authenticatorSelection (-> (AuthenticatorSelectionCriteria/builder)
                                                   (.residentKey ResidentKeyRequirement/REQUIRED)
                                                   (.userVerification UserVerificationRequirement/REQUIRED) .build))
                      (.extensions (-> (RegistrationExtensionInputs/builder) .credProps .build))
                      (.timeout 300000) .build)
          request (.startRegistration (relying-party conn settings) options)]
      (save! conn account browser "register" (.toJson request) (.toCredentialsCreateJson request) label))))

(defn begin-authentication!
  "Identifier-first passkey login. The controller must bind the random browser
  secret to its same-origin session; this request grants no account authority."
  [conn settings did browser]
  (let [account (account! conn did)
        options (-> (StartAssertionOptions/builder) (.username did) (.userVerification UserVerificationRequirement/REQUIRED) (.timeout 300000) .build)
        request (.startAssertion (relying-party conn settings) options)]
    (save! conn account browser "authenticate" (.toJson request) (.toCredentialsGetJson request) nil)))

(defn- claim! [conn id browser ceremony]
  (transaction! conn) (browser! browser)
  (when-not (and (string? id) (re-matches #"[A-Za-z0-9_-]{43}" id)) (invalid!))
  (let [digest (crypto/digest-token id)
        candidate (first (db/query conn "SELECT did, browser_hash FROM webauthn_challenges WHERE challenge_hash = ? AND ceremony = ?" digest ceremony))]
    (when-not (and candidate (MessageDigest/isEqual (codec/utf8 (:browser_hash candidate)) (codec/utf8 (crypto/digest-token browser)))) (invalid!))
    ;; Account -> challenge -> credential is the common lock order. Recheck after
    ;; acquiring the account: a concurrent mutation may have invalidated state.
    (let [account (account! conn (:did candidate))
          row (first (db/query conn "UPDATE webauthn_challenges SET used_at = ? WHERE challenge_hash = ? AND used_at IS NULL
                                    AND expires_at > ? AND account_epoch = ? RETURNING *"
                               (timestamp) digest (timestamp) (:oauth_epoch account)))]
      (when-not row (invalid!))
      [account row])))
(defn- response-json [value]
  (when-not (and (string? value) (<= 1 (count value) 65536)) (invalid!))
  (let [bytes (codec/utf8 value)]
    (when (> (alength bytes) 65536) (invalid!))
    (let [body (request/json-value bytes)
          client-data (get-in body ["response" "clientDataJSON"])]
      (when-not (and (map? body) (string? client-data) (<= 1 (count client-data) 16384)) (invalid!))
      (let [data (request/json-value (crypto/unb64 client-data))]
        ;; Yubico validates the signed origin; additionally forbid embedding in
        ;; cross-origin frames. Browser UI is intentionally top-level only.
        (when-not (and (map? data) (or (not (contains? data "crossOrigin")) (false? (get data "crossOrigin")))
                       (not (contains? data "topOrigin"))) (invalid!)))))
  value)
(defn- verified [f]
  ;; Untrusted ceremony responses may fail JSON, CBOR, key or signature validation.
  ;; Do not include their payloads in errors returned to the browser.
  (try {:result (f)}
       (catch com.yubico.webauthn.exception.RegistrationFailedException _ {:error "InvalidPasskey" :status 401})
       (catch com.yubico.webauthn.exception.AssertionFailedException _ {:error "InvalidPasskey" :status 401})
       (catch java.io.IOException _ {:error "InvalidPasskey" :status 401})
       (catch IllegalArgumentException _ {:error "InvalidPasskey" :status 401})
       (catch clojure.lang.ExceptionInfo e
         (if (:xrpc (ex-data e)) {:error "InvalidPasskey" :status 401} (throw e)))))

(defn finish-registration!
  "Consume even an invalid response: commit returned errors. A successful write
  and challenge use must commit together. Never trust client-supplied options."
  [conn settings id browser response]
  (let [[account row] (claim! conn id browser "register")
        result (verified #(let [response (PublicKeyCredential/parseRegistrationResponseJson (response-json response))]
                            (.finishRegistration (relying-party conn settings)
                              (-> (FinishRegistrationOptions/builder)
                                  (.request (PublicKeyCredentialCreationOptions/fromJson (:request row)))
                                  (.response response) .build))))]
    (if (:error result) result
      (let [registration (:result result) id (.getBytes (.getId (.getKeyId registration)))
            key (.getBytes (.getPublicKeyCose registration)) did (:did account)]
        (if (or (not (.isUserVerified registration)) (false? (.orElse (.isDiscoverable registration) true))
                (not (<= 1 (alength id) 1023)) (not (<= 1 (alength key) 4096))
                (>= (:n (first (db/query conn "SELECT count(*) AS n FROM account_passkeys WHERE did = ?" did))) max-credentials))
          {:error "InvalidPasskey" :status 401}
          (if (zero? (db/execute! conn "INSERT INTO account_passkeys(credential_id, did, label, public_key_cose, signature_count, backup_eligible, backed_up, transports)
                                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (credential_id) DO NOTHING"
                                  id did (:label row) key (.getSignatureCount registration) (.isBackupEligible registration) (.isBackedUp registration)
                                  (json/write-str (mapv #(.getId %) (.orElse (.getTransports (.getKeyId registration)) #{})))))
            {:error "InvalidPasskey" :status 401}
            ;; Adding a way in must not throw the owner out. The caller just
            ;; proved the password on a full session, and the other
            ;; implementations keep sessions, app passwords and grants intact,
            ;; so revoking them here only breaks the shared console.
            {:credential-id (crypto/b64 id) :label (:label row)}))))))

(defn finish-authentication!
  "Return a user-verified principal for the caller to consume in this transaction.
  This does not create a session or bypass any additional account factor."
  [conn settings id browser response]
  (let [[account row] (claim! conn id browser "authenticate")
        result (verified #(let [response (PublicKeyCredential/parseAssertionResponseJson (response-json response))]
                            (.finishAssertion (relying-party conn settings)
                              (-> (FinishAssertionOptions/builder) (.request (AssertionRequest/fromJson (:request row))) (.response response) .build))))]
    (if (:error result) result
      (let [assertion (:result result)]
        (if-not (and (.isSuccess assertion) (.isUserVerified assertion) (= (:did account) (.getUsername assertion)))
          {:error "InvalidPasskey" :status 401}
          (do
            (db/execute! conn "UPDATE account_passkeys SET signature_count = ?, backed_up = ?, last_used_at = ? WHERE credential_id = ? AND did = ?"
                         (.getSignatureCount assertion) (.isBackedUp assertion) (timestamp) (.getBytes (.getCredentialId assertion)) (:did account))
            {:did (:did account) :account-epoch (:oauth_epoch account) :authentication :passkey
             :credential-id (.getBase64Url (.getCredentialId assertion))}))))))

(defn list-credentials [conn did]
  (mapv (fn [row] {:id (crypto/b64 (:credential_id row)) :name (:label row)
                  :created-at (str (:created_at row)) :last-used-at (some-> (:last_used_at row) str)
                  :backup-eligible (:backup_eligible row) :backed-up (:backed_up row)})
        (db/query conn "SELECT credential_id, label, created_at, last_used_at, backup_eligible, backed_up FROM account_passkeys WHERE did = ? ORDER BY created_at, credential_id" did)))
(defn remove!
  "Require recent owner authentication and CSRF at the management boundary."
  [conn did credential-id]
  (account! conn did)
  (let [id (try (when (and (string? credential-id) (<= 1 (count credential-id) 1364)) (crypto/unb64 credential-id)) (catch Exception _ nil))]
    (when-not (and id (pos? (db/execute! conn "DELETE FROM account_passkeys WHERE did = ? AND credential_id = ?" did id)))
      (errors/raise! 400 "PasskeyNotFound" "Passkey was not found"))
    {:removed true}))
