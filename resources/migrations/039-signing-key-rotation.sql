ALTER TABLE handle_updates DROP CONSTRAINT handle_updates_operation_kind_check;
ALTER TABLE handle_updates ADD CONSTRAINT handle_updates_operation_kind_check
  CHECK (operation_kind IN ('handle', 'submit', 'rotate', 'signing'));
ALTER TABLE handle_updates ADD COLUMN next_signing_key bytea;
ALTER TABLE handle_updates ADD COLUMN next_signing_public bytea;
ALTER TABLE handle_updates ADD COLUMN previous_signing_key text;
ALTER TABLE handle_updates ADD CONSTRAINT handle_updates_signing_material_check CHECK (
  (operation_kind = 'signing' AND next_signing_key IS NOT NULL AND next_signing_public IS NOT NULL AND previous_signing_key IS NOT NULL)
  OR (operation_kind <> 'signing' AND next_signing_key IS NULL AND next_signing_public IS NULL AND previous_signing_key IS NULL)
);
CREATE TABLE signing_key_rotations (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  previous_key text NOT NULL,
  signing_public bytea NOT NULL,
  operation_cid text,
  repo_commit text NOT NULL,
  repo_rev text NOT NULL,
  completed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (did, previous_key)
);
