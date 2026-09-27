(ns pds.signing-state
  (:require [pds.db :as db]
            [pds.errors :as errors]))

(defn ready!
  "Account lock must precede repository locks. The separate query sees a queued
  rotation committed while waiting, including failed/ambiguous directory work."
  [conn did]
  (db/query conn "SELECT did FROM accounts WHERE did = ? FOR UPDATE" did)
  (when (seq (db/query conn "SELECT 1 FROM handle_updates WHERE did = ? AND operation_kind = 'signing'" did))
    (errors/raise! 503 "SigningKeyRotationPending" "Repository signing-key rotation is pending; retry later")))
