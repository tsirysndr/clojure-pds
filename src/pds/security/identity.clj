(ns pds.security.identity
  "Email-authorized recovery-key changes bound to a recent browser owner."
  (:require [pds.accounts :as accounts]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.handles :as handles]
            [pds.plc-recovery-keys :as recovery]
            [pds.request :as request]
            [pds.security.browser :as browser]))

(defn change! [ds settings token csrf body]
  (let [owner (db/transact! ds #(browser/owner! % token csrf))
        did (:did owner) expected (get body "previousCid") keys (get body "recoveryKeys")
        authorize! (fn [conn]
                     (when-not (= owner (browser/owner! conn token csrf))
                       (errors/raise! 401 "BrowserSessionRequired" "Sign in again to continue")))
        verify-change!
        (fn [conn consume?]
          (accounts/require-email! settings)
          (let [code (request/string! (get body "code") "code")
                account (first (db/query conn "SELECT * FROM accounts WHERE did = ?" did))]
            (if consume?
              (accounts/consume-token! conn "plc-operation" code did (:email account))
              (when (empty? (db/query conn "SELECT 1 FROM account_tokens WHERE token_hash = ? AND purpose = 'plc-operation'
                                            AND did = ? AND email = ? AND expires_at > now()"
                                     (crypto/digest-token code) did (:email account)))
                (errors/raise! 400 "InvalidToken" "Invalid or expired email token")))))
        queued (recovery/enqueue! ds settings did expected keys
                                 {:authorize! authorize! :verify-change! verify-change!})
        result (if (#{"completed" "unchanged"} (:state queued)) queued
                 (do (handles/process-one! ds settings did)
                     (recovery/result! ds did expected keys)))]
    ;; A committed intent survives logout. Recheck access before returning account
    ;; state after directory I/O, without undoing the already authorized change.
    (assoc (browser/action! ds settings token csrf "identity/recovery/status" {}) :result result)))
