ALTER TABLE handle_updates DROP CONSTRAINT handle_updates_operation_kind_check;
ALTER TABLE handle_updates ADD CONSTRAINT handle_updates_operation_kind_check
  CHECK (operation_kind IN ('handle', 'submit', 'rotate'));
ALTER TABLE handle_updates ADD COLUMN next_rotation_key bytea;
ALTER TABLE handle_updates ADD COLUMN next_rotation_public bytea;
ALTER TABLE handle_updates ADD CONSTRAINT handle_updates_rotation_material_check CHECK (
  (operation_kind = 'rotate' AND next_rotation_key IS NOT NULL AND next_rotation_public IS NOT NULL)
  OR (operation_kind <> 'rotate' AND next_rotation_key IS NULL AND next_rotation_public IS NULL)
);

-- Public receipts make retries of an expected head idempotent even after later
-- identity operations. Superseded private keys are not retained here.
CREATE TABLE plc_key_rotations (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  previous_cid text NOT NULL,
  operation_cid text NOT NULL,
  rotation_public bytea NOT NULL,
  completed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (did, previous_cid)
);
