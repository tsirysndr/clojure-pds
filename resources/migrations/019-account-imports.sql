ALTER TABLE plc_identities DROP CONSTRAINT plc_identities_status_check;
ALTER TABLE plc_identities ADD CONSTRAINT plc_identities_status_check
  CHECK (status IN ('pending', 'working', 'ready', 'failed', 'prepared'));

-- Preserve provenance after local deletion; a foreign web DID remains external.
ALTER TABLE accounts ADD COLUMN imported boolean NOT NULL DEFAULT false;

CREATE TABLE account_imports (
  did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
  source_document jsonb NOT NULL,
  repository_imported boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
