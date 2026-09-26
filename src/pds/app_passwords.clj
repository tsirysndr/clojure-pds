(ns pds.app-passwords
  (:require [clojure.string :as str]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.codec :as codec]
            [pds.request :as request])
  (:import [java.util UUID]))

(defn normalize [password]
  (when (and (string? password) (<= (count password) 19))
    (let [value (str/lower-case password)]
      (when (or (re-matches #"[a-z2-7]{16}" value) (re-matches #"[a-z2-7]{4}(?:-[a-z2-7]{4}){3}" value))
        (str/replace value "-" "")))))

(defn digest [settings did normalized]
  ;; Machine-generated 80-bit secrets permit indexed lookup without
  ;; scanning password hashes. A purpose-derived key and DID bind the digest.
  (crypto/b64 (crypto/hmac (crypto/hmac (:master-key settings) (codec/utf8 "clojure-pds/app-password/v1"))
                           (codec/utf8 (str did "\u0000" normalized)))))

(defn find-password [conn settings did password]
  (when-let [normalized (normalize password)]
    (first (db/query conn "SELECT id, privileged FROM app_passwords WHERE did = ? AND password_digest = ?"
                    did (digest settings did normalized)))))

(defn- name! [value]
  (request/string! value "name")
  (when (> (alength (codec/utf8 value)) 256) (errors/invalid! "App password name exceeds 256 UTF-8 bytes"))
  value)

(defn- public-password [row]
  {:name (:name row) :privileged (:privileged row)
   :createdAt (str (.toInstant ^java.sql.Timestamp (:created_at row)))})

(defn create! [conn settings account body]
  (auth/require-primary! account)
  (let [name (name! (get body "name")) privileged (get body "privileged" false) did (:did account)]
    (when-not (boolean? privileged) (errors/invalid! "privileged must be a boolean"))
    ;; Login/create/revoke/reset all lock the account first.
    (db/query conn "SELECT did FROM accounts WHERE did = ? FOR UPDATE" did)
    (when (seq (db/query conn "SELECT 1 FROM app_passwords WHERE did = ? AND name = ?" did name))
      (errors/invalid! "An app password with this name already exists"))
    (when (>= (:count (first (db/query conn "SELECT count(*) AS count FROM app_passwords WHERE did = ?" did))) 100)
      (errors/invalid! "An account may have at most 100 app passwords"))
    (let [secret (codec/base32 (crypto/random-bytes 10))
          row (first (db/query conn "INSERT INTO app_passwords(id, did, name, password_digest, privileged)
                                    VALUES (?, ?, ?, ?, ?) RETURNING name, privileged, created_at"
                               (UUID/randomUUID) did name (digest settings did secret) privileged))]
      (assoc (public-password row) :password (str/join "-" (map #(apply str %) (partition 4 secret)))))))

(defn list-passwords [conn account]
  {:passwords (mapv public-password (db/query conn "SELECT name, privileged, created_at FROM app_passwords WHERE did = ? ORDER BY created_at DESC, name"
                                             (:did account)))})

(defn revoke! [conn account body]
  (let [name (name! (get body "name"))]
    (db/query conn "SELECT did FROM accounts WHERE did = ? FOR UPDATE" (:did account))
    ;; FK cascades delete all dependent sessions and refresh tokens atomically.
    (db/execute! conn "DELETE FROM app_passwords WHERE did = ? AND name = ?" (:did account) name)))
