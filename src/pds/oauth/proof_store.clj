(ns pds.oauth.proof-store
  (:require [pds.db :as db]
            [pds.oauth.dpop :as dpop])
  (:import [java.time Instant]))

(defn consume!
  "Consume a verified DPoP candidate once in the supplied transaction. Uniqueness
  is key+jti across endpoints and server nonce rotations, not JWT signature bytes."
  [conn {:keys [jkt jti-hash expires-at]}]
  (when (.getAutoCommit ^java.sql.Connection conn)
    (throw (ex-info "DPoP consumption requires a transaction" {})))
  (let [timestamp (dpop/now) now (Instant/ofEpochSecond timestamp)]
    (when-not (< timestamp expires-at) (dpop/error! "invalid_dpop_proof"))
    (db/execute! conn "DELETE FROM oauth_dpop_uses WHERE (jkt, jti_hash) IN
                       (SELECT jkt, jti_hash FROM oauth_dpop_uses WHERE expires_at <= ?
                        ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)" now)
    (when (zero? (db/execute! conn "INSERT INTO oauth_dpop_uses(jkt, jti_hash, expires_at) VALUES (?, ?, ?)
                                  ON CONFLICT (jkt, jti_hash) DO UPDATE SET expires_at = EXCLUDED.expires_at
                                  WHERE oauth_dpop_uses.expires_at <= ?"
                             jkt jti-hash (Instant/ofEpochSecond expires-at) now))
      (dpop/error! "invalid_dpop_proof")))
  nil)

(defn accept!
  "Verify and durably consume before accepting a request. The owned transaction
  commits before business logic, so application errors cannot revive a used proof.
  Database outages fail closed; there is no process-local replay fallback."
  [ds settings token context]
  (let [proof (dpop/verify! settings token context)]
    (db/transact! ds #(consume! % proof))
    proof))
