(ns pds.reserved-keys
  "Reserved repository signing keys for later account creation. Reservations
  are public, bounded, expire after a day and never expose private material."
  (:require [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.identity :as identity]
            [pds.plc :as plc]))

(def capacity 4096)

(defn- did-key [public] (plc/did-key {:algorithm "ES256" :public public}))
(defn- purpose [key-did] (str "pds/reserved-signing-key/v1/" key-did))

(defn reserve! [ds settings body]
  (let [did (get body "did")]
    (when (and did (not (identity/supported-did? did)))
      (errors/invalid! "Expected a supported account DID"))
    (db/transact! ds
      (fn [conn]
        (db/execute! conn "DELETE FROM reserved_signing_keys WHERE created_at < now() - interval '24 hours'")
        (or (when did
              (when-let [existing (first (db/query conn "SELECT public_key FROM reserved_signing_keys WHERE did = ?" did))]
                {:signingKey (did-key (:public_key existing))}))
            (let [pending (:n (first (db/query conn "SELECT count(*) AS n FROM reserved_signing_keys")))]
              (when (>= pending capacity)
                (errors/raise! 503 "ReservationBusy" "Signing key reservations are full; retry later"))
              (let [key (crypto/keypair "ES256") key-did (did-key (:public key))]
                (try
                  (db/execute! conn "INSERT INTO reserved_signing_keys(key_did, did, signing_key, public_key) VALUES (?, ?, ?, ?)"
                               key-did did (crypto/seal (:master-key settings) (purpose key-did) (:private key)) (:public key))
                  (catch java.sql.SQLException e
                    (when-not (= "23505" (.getSQLState e)) (throw e))
                    (errors/raise! 409 "ConcurrentReservation" "A reservation for this DID was just created; retry")))
                {:signingKey key-did})))))))

(defn consume!
  "Remove and decrypt the reservation for an account DID inside the caller's
  transaction; nil when none exists."
  [conn settings did]
  (when-let [row (first (db/query conn "DELETE FROM reserved_signing_keys WHERE did = ? RETURNING key_did, signing_key, public_key" did))]
    {:algorithm "ES256"
     :private (crypto/unseal (:master-key settings) (purpose (:key_did row)) (:signing_key row))
     :public (:public_key row)}))
