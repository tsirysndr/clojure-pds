CREATE TABLE reserved_signing_keys (
  key_did text PRIMARY KEY,
  did text UNIQUE,
  signing_key bytea NOT NULL,
  public_key bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
