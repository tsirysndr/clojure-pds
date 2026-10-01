(ns pds.security.passkey-ceremony-test
  "A full ceremony against a software authenticator.

  The registration and sign-in paths only ever fail in production with a flat
  `InvalidPasskey`, by design: nothing about an untrusted ceremony response is
  reflected back. That makes them easy to break silently - a payload the
  WebAuthn library refuses on shape is indistinguishable from a forged
  signature. This exercises both ceremonies end to end with the exact payload
  the gateway console sends: the credential as a parsed object, no
  clientExtensionResults, driven from the parent-domain origin while this node
  keeps its own hostname."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [pds.api.auth]
            [pds.db :as db]
            [pds.crypto :as crypto]
            [pds.security.passkeys :as passkeys])
  (:import (java.io ByteArrayOutputStream File)
           (java.security KeyPairGenerator MessageDigest Signature)
           (java.security.spec ECGenParameterSpec)
           (java.security.interfaces ECPublicKey)
           (java.util Base64)
           (com.fasterxml.jackson.dataformat.cbor CBORFactory)))

(defn- b64url [^bytes b] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))
(defn- unb64url [^String s] (.decode (Base64/getUrlDecoder) s))
(defn- sha256 [^bytes b] (.digest (MessageDigest/getInstance "SHA-256") b))

(defn- unsigned-32 [^java.math.BigInteger n]
  (let [raw (.toByteArray n) out (byte-array 32)]
    (if (> (alength raw) 32)
      (System/arraycopy raw (- (alength raw) 32) out 0 32)
      (System/arraycopy raw 0 out (- 32 (alength raw)) (alength raw)))
    out))

(defn- cose-key ^bytes [^ECPublicKey pub]
  (let [out (ByteArrayOutputStream.) gen (.createGenerator (CBORFactory.) out)]
    (doto gen
      (.writeStartObject)
      (.writeFieldId 1) (.writeNumber 2)
      (.writeFieldId 3) (.writeNumber -7)
      (.writeFieldId -1) (.writeNumber 1)
      (.writeFieldId -2) (.writeBinary (unsigned-32 (.getAffineX (.getW pub))))
      (.writeFieldId -3) (.writeBinary (unsigned-32 (.getAffineY (.getW pub))))
      (.writeEndObject) (.close))
    (.toByteArray out)))

(defn- auth-data ^bytes [rp-id flags counter attested]
  (let [out (ByteArrayOutputStream.)]
    (.write out ^bytes (sha256 (.getBytes ^String rp-id "UTF-8")))
    (.write out (int flags))
    (.write out (byte-array [0 0 0 counter]))
    (when attested
      (let [{:keys [cred-id cose]} attested]
        (.write out (byte-array 16))
        (.write out (byte-array [(bit-shift-right (alength ^bytes cred-id) 8) (bit-and (alength ^bytes cred-id) 0xff)]))
        (.write out ^bytes cred-id)
        (.write out ^bytes cose)))
    (.toByteArray out)))

(defn- attestation-object ^bytes [^bytes authenticator-data]
  (let [out (ByteArrayOutputStream.) gen (.createGenerator (CBORFactory.) out)]
    (doto gen
      (.writeStartObject)
      (.writeFieldName "fmt") (.writeString "none")
      (.writeFieldName "attStmt") (.writeStartObject) (.writeEndObject)
      (.writeFieldName "authData") (.writeBinary authenticator-data)
      (.writeEndObject) (.close))
    (.toByteArray out)))

(defn- client-data ^bytes [kind challenge origin]
  (.getBytes (json/write-str {"type" kind "challenge" challenge "origin" origin "crossOrigin" false}) "UTF-8"))

(defn- sign ^bytes [private ^bytes authenticator-data ^bytes client]
  (let [s (doto (Signature/getInstance "SHA256withECDSA") (.initSign private))]
    (.update s authenticator-data) (.update s ^bytes (sha256 client)) (.sign s)))

(def ^:private origin "https://rocksky.social")
(def ^:private settings
  {:public-url "https://orangepi-zero-3w.rocksky.social"
   :webauthn-rp-id "rocksky.social"
   :webauthn-origins [origin]})

(deftest a_console_shaped_ceremony_registers_and_signs_in
  (let [path (str (File/createTempFile "passkey-ceremony" ".sqlite3"))
        ds (db/datasource (db/settings {"PDS_SQLITE_PATH" path}))
        _ (db/migrate! ds)
        did "did:web:alice.test"
        as-json (deref #'pds.api.auth/credential-json)
        keypair (let [g (KeyPairGenerator/getInstance "EC")]
                  (.initialize g (ECGenParameterSpec. "secp256r1")) (.generateKeyPair g))
        cred-id (crypto/random-bytes 32)]
    (db/transact! ds (fn [conn]
      (db/execute! conn "INSERT INTO accounts(did, handle, email, password_hash) VALUES (?,?,?,?)"
                   did "alice.rocksky.social" "alice@example.com" "x")))

    (testing "registration from the parent-domain origin, credential as an object"
      (let [browser (crypto/token)
            ceremony (db/transact! ds (fn [conn] (passkeys/begin-registration! conn settings did browser "Macbook Air")))
            public-key (let [o (:options ceremony)] (or (get o "publicKey") o))
            _ (is (= "rocksky.social" (get-in public-key ["rp" "id"]))
                  "the ceremony is offered under the shared relying party")
            client (client-data "webauthn.create" (get public-key "challenge") origin)
            credential {"id" (b64url cred-id) "rawId" (b64url cred-id) "type" "public-key"
                        "response" {"clientDataJSON" (b64url client)
                                    "attestationObject" (b64url (attestation-object
                                                                  (auth-data "rocksky.social" 0x45 0
                                                                             {:cred-id cred-id :cose (cose-key (.getPublic keypair))})))}}
            result (db/transact! ds (fn [conn]
                     (passkeys/finish-registration! conn settings (:id ceremony) browser (as-json credential))))]
        (is (= (b64url cred-id) (:credential-id result))
            (str "registration refused: " (pr-str result)))
        (is (= "Macbook Air" (:label result)))
        ;; Adding a way in must not throw the owner out: the epoch that sessions,
        ;; grants and outstanding challenges hang off stays where it was, as on
        ;; the other implementations.
        (is (= 0 (db/transact! ds (fn [conn]
                   (:oauth_epoch (first (db/query conn "SELECT oauth_epoch FROM accounts WHERE did = ?" did)))))))))

    (testing "the listing carries timestamps a browser can parse"
      (let [listed (db/transact! ds (fn [conn] (passkeys/list-credentials conn did)))
            stamp (:created-at (first listed))]
        ;; ISO 8601 UTC, not java.sql.Timestamp's "2026-10-01 18:23:20.014",
        ;; which new Date() refuses in strict engines and shows as Invalid Date.
        (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z" (str stamp))
            (str "not ISO 8601 UTC: " (pr-str stamp)))))

    (testing "signing in with that credential, same origin"
      (let [browser (crypto/token)
            ceremony (db/transact! ds (fn [conn] (passkeys/begin-authentication! conn settings did browser)))
            public-key (let [o (:options ceremony)] (or (get o "publicKey") o))
            client (client-data "webauthn.get" (get public-key "challenge") origin)
            user-handle (db/transact! ds (fn [conn]
                          (:user_handle (first (db/query conn "SELECT user_handle FROM account_webauthn_users WHERE did = ?" did)))))
            authenticator (auth-data "rocksky.social" 0x05 1 nil)
            credential {"id" (b64url cred-id) "rawId" (b64url cred-id) "type" "public-key"
                        "response" {"clientDataJSON" (b64url client)
                                    "authenticatorData" (b64url authenticator)
                                    "signature" (b64url (sign (.getPrivate keypair) authenticator client))
                                    "userHandle" (b64url user-handle)}}
            result (db/transact! ds (fn [conn]
                     (passkeys/finish-authentication! conn settings (:id ceremony) browser (as-json credential))))]
        (is (= did (:did result)) (str "sign-in refused: " (pr-str result)))))))
