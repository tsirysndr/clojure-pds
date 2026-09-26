(ns pds.service-auth
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax])
  (:import [java.net URI]))

;; Match the reference PDS's case-insensitive policy at the pinned revision in
;; docs/COMPATIBILITY.md. Keep this policy shared with future proxy authorization.
(def protected-methods
  (set (map str/lower-case
            ["com.atproto.admin.sendEmail" "com.atproto.identity.requestPlcOperationSignature"
             "com.atproto.identity.signPlcOperation" "com.atproto.identity.updateHandle"
             "com.atproto.server.activateAccount" "com.atproto.server.confirmEmail"
             "com.atproto.server.createAppPassword" "com.atproto.server.deactivateAccount"
             "com.atproto.server.getAccountInviteCodes" "com.atproto.server.getSession"
             "com.atproto.server.listAppPasswords" "com.atproto.server.requestAccountDelete"
             "com.atproto.server.requestEmailConfirmation" "com.atproto.server.requestEmailUpdate"
             "com.atproto.server.revokeAppPassword" "com.atproto.server.updateEmail"])))
(def privileged-methods
  (set (map str/lower-case
            ["com.atproto.server.createAccount" "chat.bsky.actor.deleteAccount" "chat.bsky.actor.exportAccountData"
             "chat.bsky.convo.deleteMessageForSelf" "chat.bsky.convo.getConvo" "chat.bsky.convo.getConvoForMembers"
             "chat.bsky.convo.getLog" "chat.bsky.convo.getMessages" "chat.bsky.convo.leaveConvo"
             "chat.bsky.convo.listConvos" "chat.bsky.convo.muteConvo" "chat.bsky.convo.sendMessage"
             "chat.bsky.convo.sendMessageBatch" "chat.bsky.convo.unmuteConvo" "chat.bsky.convo.updateRead"])))

(defn audience? [value]
  (boolean
    (and (string? value) (<= 1 (count value) 2048)
         (let [[did fragment :as parts] (str/split value #"#" -1)]
           (and (<= (count parts) 2) (syntax/did? did)
                (or (= 1 (count parts))
                    (re-matches #"(?:[A-Za-z0-9._~!$&'()*+,;=:@/?-]|%[0-9A-Fa-f]{2})+" fragment))
                (or (re-matches #"did:plc:[a-z2-7]{24}" did)
                    (and (str/starts-with? did "did:web:")
                         (not (str/includes? (subs did 8) ":"))
                         (try
                           (let [uri (URI/create (str "https://" (str/replace (subs did 8) "%3A" ":")))]
                             (and (seq (.getHost uri)) (nil? (.getUserInfo uri))
                                  (or (= -1 (.getPort uri))
                                      (and (= "localhost" (.getHost uri)) (<= 0 (.getPort uri) 65535)))))
                           (catch Exception _ false)))))))))

(defn parameters! [params now]
  (let [audience (get params "aud") method (get params "lxm") raw-exp (get params "exp")]
    (when-not (audience? audience) (errors/invalid! "aud must be an AT Protocol DID or DID service reference"))
    (when (and (contains? params "lxm") (not (syntax/nsid? method))) (errors/invalid! "lxm must be an NSID"))
    (let [expires (if (contains? params "exp")
                    (or (when (and (string? raw-exp) (re-matches #"-?[0-9]{1,19}" raw-exp)) (parse-long raw-exp))
                        (errors/raise! 400 "BadExpiration" "exp must be a Unix timestamp in seconds"))
                    (+ now 60))]
      (when-not (< now expires (+ now (if method 3600 60) 1))
        (errors/raise! 400 "BadExpiration" "Expiration must be in the future, at most one hour for a method or one minute without one"))
      {:audience audience :method method :expires expires})))

(defn authorize! [account method]
  (let [method (some-> method str/lower-case)]
    (when (and (= "taken_down" (:status account)) (not= "com.atproto.server.createaccount" method))
      (errors/raise! 400 "InvalidToken" "Taken-down accounts may only authorize account migration"))
    (when (protected-methods method) (errors/invalid! "This method cannot use service authentication"))
    (when (and (privileged-methods method)
               (not (#{"com.atproto.access" "com.atproto.appPassPrivileged"} (:access-scope account))))
      (errors/invalid! "A primary or privileged app-password session is required for this method"))))

(defn sign
  "Sign a short-lived service JWT using the repository key, not the session key.
  Callers authorize the audience, method and lifetime before signing."
  [key issuer audience method issued expires]
  (let [header {"typ" "JWT" "alg" (:algorithm key) "kid" "#atproto"}
        claims (cond-> {"iss" issuer "aud" audience "iat" issued "exp" expires "jti" (crypto/token)}
                 method (assoc "lxm" method))
        unsigned (str (crypto/b64 (codec/utf8 (json/write-str header))) "."
                      (crypto/b64 (codec/utf8 (json/write-str claims))))]
    (str unsigned "." (crypto/b64 (crypto/sign (:algorithm key) (:private key) (codec/utf8 unsigned))))))

(defn issue! [conn settings account params]
  (let [now (auth/now) {:keys [audience method expires]} (parameters! params now)]
    (authorize! account method)
    (let [repo (first (db/query conn "SELECT signing_key FROM repositories WHERE did = ?" (:did account)))]
      (when-not repo (errors/raise! 400 "RepoNotFound" "Repository was not found"))
      {:token (sign {:algorithm "ES256" :private (crypto/unseal (:master-key settings) (:did account) (:signing_key repo))}
                    (:did account) audience method now expires)})))
