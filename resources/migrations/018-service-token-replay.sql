-- Issuers may be remote accounts which do not yet exist on this PDS.
CREATE TABLE service_token_uses (
  issuer text NOT NULL,
  nonce_hash text NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (issuer, nonce_hash)
);
CREATE INDEX service_token_uses_expiry ON service_token_uses(expires_at);
