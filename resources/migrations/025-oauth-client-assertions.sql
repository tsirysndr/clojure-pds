-- Hash client IDs as well as jti values to bound the composite unique index.
-- Assertions are single-use across every signing key published by a client.
CREATE TABLE oauth_client_assertion_uses (
  client_id_hash text NOT NULL CHECK (client_id_hash ~ '^[A-Za-z0-9_-]{43}$'),
  jti_hash text NOT NULL CHECK (jti_hash ~ '^[A-Za-z0-9_-]{43}$'),
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (client_id_hash, jti_hash)
);
CREATE INDEX oauth_client_assertion_uses_expiry ON oauth_client_assertion_uses(expires_at);
