-- DPoP public keys identify clients/sessions which need not have an account yet.
-- Store only the thumbprint and hashed jti; proof JWTs and access tokens stay out.
CREATE TABLE oauth_dpop_uses (
  jkt text NOT NULL CHECK (jkt ~ '^[A-Za-z0-9_-]{43}$'),
  jti_hash text NOT NULL CHECK (jti_hash ~ '^[A-Za-z0-9_-]{43}$'),
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (jkt, jti_hash)
);
CREATE INDEX oauth_dpop_uses_expiry ON oauth_dpop_uses(expires_at);
