ALTER TABLE handle_updates DROP CONSTRAINT handle_updates_operation_kind_check;
ALTER TABLE handle_updates ADD CONSTRAINT handle_updates_operation_kind_check
  CHECK (operation_kind IN ('handle', 'submit', 'rotate', 'signing', 'recovery'));

CREATE TABLE plc_recovery_key_changes (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  previous_cid text NOT NULL,
  operation_cid text NOT NULL,
  recovery_keys jsonb NOT NULL,
  server_key text NOT NULL,
  completed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (did, previous_cid)
);
