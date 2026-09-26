ALTER TABLE accounts DROP CONSTRAINT accounts_status_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_status_check
  CHECK (status IN ('active', 'deactivated', 'taken_down', 'deleted', 'provisioning'));

-- The signed operation and both keys are durable before any directory write.
-- Pending accounts reserve handle/email/invite use but cannot authenticate.
CREATE TABLE plc_identities (
  did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
  directory_url text NOT NULL,
  operation bytea NOT NULL,
  operation_cid text NOT NULL,
  rotation_key bytea NOT NULL,
  rotation_public bytea NOT NULL,
  recovery_key text,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'working', 'ready', 'failed')),
  attempts bigint NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_token uuid,
  lease_until timestamptz,
  last_error text,
  confirmed_at timestamptz,
  CHECK ((status = 'working') = (lease_token IS NOT NULL)),
  CHECK ((status = 'working') = (lease_until IS NOT NULL))
);
CREATE INDEX plc_provision_due ON plc_identities(available_at) WHERE status = 'pending';
CREATE INDEX plc_provision_lease ON plc_identities(lease_until) WHERE status = 'working';
