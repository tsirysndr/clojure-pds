CREATE TABLE handle_reservations (
  handle text PRIMARY KEY,
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  permanent boolean NOT NULL DEFAULT false,
  UNIQUE(handle, did)
);
INSERT INTO handle_reservations(handle, did, permanent)
  SELECT handle, did, status = 'deleted' OR did = 'did:web:' || handle FROM accounts;
INSERT INTO handle_reservations(handle, did, permanent)
  SELECT substring(did from 9), did, true FROM accounts WHERE did LIKE 'did:web:%'
  ON CONFLICT (handle) DO NOTHING;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM accounts a JOIN handle_reservations h ON h.handle = substring(a.did from 9)
             WHERE a.did LIKE 'did:web:%' AND a.did <> h.did) THEN
    RAISE EXCEPTION 'A did:web hostname is reserved by a different account';
  END IF;
END $$;

CREATE TABLE handle_updates (
  did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
  target_handle text NOT NULL,
  external_handle boolean NOT NULL,
  operation bytea NOT NULL,
  operation_cid text NOT NULL,
  directory_url text NOT NULL,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'working', 'failed')),
  attempts bigint NOT NULL DEFAULT 0,
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_token uuid,
  lease_until timestamptz,
  last_error text,
  FOREIGN KEY(target_handle, did) REFERENCES handle_reservations(handle, did),
  CHECK ((status = 'working') = (lease_token IS NOT NULL)),
  CHECK ((status = 'working') = (lease_until IS NOT NULL))
);
CREATE INDEX handle_updates_due ON handle_updates(available_at) WHERE status = 'pending';
CREATE INDEX handle_updates_lease ON handle_updates(lease_until) WHERE status = 'working';
