(ns pds.invites
  (:require [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.syntax :as syntax]
            [pds.request :as request]))

(defn settings [env]
  (let [required (get env "PDS_REQUIRE_INVITE_CODE" "false")]
    (when-not (#{"true" "false"} required)
      (throw (ex-info "PDS_REQUIRE_INVITE_CODE must be true or false" {})))
    {:invite-required (= "true" required)}))

(defn count! [value field maximum]
  (when-not (and (integer? value) (<= 1 value maximum))
    (errors/invalid! (str field " must be an integer from 1 to " maximum))) value)

(defn owner! [value]
  (when-not (syntax/did? value) (errors/invalid! "Invalid invite owner DID")) value)

(defn create! [conn owner uses]
  (let [code (crypto/b64 (crypto/random-bytes 18))]
    (db/execute! conn "INSERT INTO invite_codes(code, available, for_account) VALUES (?, ?, ?)" code uses owner)
    code))

(defn create-code! [conn body]
  {:code (create! conn (if (contains? body "forAccount") (owner! (get body "forAccount")) "admin")
                  (count! (get body "useCount") "useCount" 1000000))})

(defn create-codes! [conn body]
  (let [n (count! (get body "codeCount") "codeCount" 1000)
        uses (count! (get body "useCount") "useCount" 1000000)
        owners (get body "forAccounts" ["admin"])]
    (when-not (and (vector? owners) (<= 1 (count owners) 1000) (<= (* n (count owners)) 1000))
      (errors/invalid! "At most 1000 codes may be created per request"))
    (when (contains? body "forAccounts") (doseq [owner owners] (owner! owner)))
    {:codes (mapv (fn [owner] {:account owner :codes (mapv (fn [_] (create! conn owner uses)) (range n))}) owners)}))

(defn consume! [conn settings code did]
  (when (and (:invite-required settings) (nil? code))
    (errors/raise! 400 "InvalidInviteCode" "An invite code is required"))
  (when (some? code)
    (when-not (and (string? code) (<= 1 (count code) 256))
      (errors/raise! 400 "InvalidInviteCode" "Invalid invite code"))
    ;; The row lock serializes redemption with other signups and disabling.
    (let [invite (first (db/query conn "SELECT * FROM invite_codes WHERE code = ? FOR UPDATE" code))
          uses (:n (first (db/query conn "SELECT count(*) AS n FROM invite_uses WHERE code = ?" code)))
          owner (when invite (first (db/query conn "SELECT status FROM accounts WHERE did = ?" (:for_account invite))))]
      (when-not (and invite (not (:disabled invite)) (< uses (:available invite))
                     (not (#{"taken_down" "deleted" "provisioning"} (:status owner))))
        (errors/raise! 400 "InvalidInviteCode" "Invite code is unavailable"))
      (db/execute! conn "INSERT INTO invite_uses(code, used_by) VALUES (?, ?)" code did))))

(defn view [conn row]
  {:code (:code row) :available (:available row) :disabled (:disabled row)
   :forAccount (:for_account row) :createdBy (:created_by row)
   :createdAt (str (.toInstant ^java.sql.Timestamp (:created_at row)))
   :uses (mapv (fn [use] {:usedBy (:used_by use) :usedAt (str (.toInstant ^java.sql.Timestamp (:used_at use)))})
               (db/query conn "SELECT * FROM invite_uses WHERE code = ? ORDER BY used_at, used_by" (:code row)))})

(defn account-codes [conn did params]
  (doseq [k ["includeUsed" "createAvailable"]]
    (when (and (contains? params k) (not (#{"true" "false"} (get params k))))
      (errors/invalid! (str k " must be true or false"))))
  {:codes (mapv #(view conn %) (db/query conn
    "SELECT c.* FROM invite_codes c WHERE for_account = ?
       AND (? OR (NOT disabled AND available > (SELECT count(*) FROM invite_uses u WHERE u.code = c.code)))
       ORDER BY created_at, code" did (not= "false" (get params "includeUsed"))))})

(defn list-codes
  "Administrative listing of every invite code, newest or most-used first, with
  an opaque sort-value:code keyset cursor."
  [conn params]
  (let [limit (request/limit! params 100 500)
        order (get params "sort" "recent")
        [value code] (some-> (get params "cursor") (str/split #":" 2))]
    (when-not (#{"recent" "usage"} order) (errors/invalid! "Unknown sort order"))
    (when (contains? params "cursor")
      (when-not (and value code (re-matches #"[0-9]{1,18}" value) (<= 1 (count code) 256))
        (errors/invalid! "Invalid cursor")))
    (let [sort-value (case order
                       "recent" "(extract(epoch FROM c.created_at) * 1000000)::bigint"
                       "usage" "(SELECT count(*) FROM invite_uses u WHERE u.code = c.code)::bigint")
          rows (db/query conn (str "SELECT c.*, " sort-value " AS sort_value FROM invite_codes c"
                                   " WHERE ?::bigint IS NULL OR (" sort-value ", c.code) < (?, ?)"
                                   " ORDER BY sort_value DESC, c.code DESC LIMIT ?")
                         (some-> value Long/parseLong) (some-> value Long/parseLong) (or code "") limit)]
      (cond-> {:codes (mapv #(view conn %) rows)}
        (= (count rows) limit)
        (assoc :cursor (str (:sort_value (last rows)) ":" (:code (last rows))))))))

(defn disable! [conn body]
  ;; Bulk operations can overlap through both owner and code selectors.
  (db/query conn "SELECT pg_advisory_xact_lock(731946283)")
  (doseq [[field column] [["codes" "code"] ["accounts" "for_account"]]]
    (when (contains? body field)
      (let [values (get body field)]
        (when-not (and (vector? values) (<= (count values) 1000)
                       (every? #(and (string? %) (<= 1 (count %) 256)) values))
          (errors/invalid! (str "Invalid " field)))
        ;; Deterministic ordering avoids deadlocks between bulk disables.
        (doseq [value (sort (distinct values))]
          (db/execute! conn (str "UPDATE invite_codes SET disabled = true WHERE " column " = ?") value))))))

(defn set-account-enabled! [conn body enabled?]
  (let [did (owner! (get body "account")) note (get body "note")]
    (when (and (some? note) (not (and (string? note) (<= (count note) 10000))))
      (errors/invalid! "Invalid invite note"))
    (when (zero? (db/execute! conn "UPDATE accounts SET invites_disabled = ?, invite_note = ? WHERE did = ? AND status <> 'deleted'"
                             (not enabled?) note did))
      (errors/raise! 400 "AccountNotFound" "Account was not found"))))
