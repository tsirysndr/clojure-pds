CREATE TABLE oauth_pkce_uses (
  challenge_hash text PRIMARY KEY CHECK (challenge_hash ~ '^[A-Za-z0-9_-]{43}$'),
  expires_at timestamptz NOT NULL
);
CREATE INDEX oauth_pkce_uses_expiry ON oauth_pkce_uses(expires_at);

CREATE TABLE oauth_par_requests (
  request_hash text PRIMARY KEY CHECK (request_hash ~ '^[A-Za-z0-9_-]{43}$'),
  client_id text NOT NULL,
  parameters jsonb NOT NULL CHECK (jsonb_typeof(parameters) = 'object'),
  client_binding jsonb NOT NULL CHECK (jsonb_typeof(client_binding) = 'object'),
  dpop_jkt text NOT NULL CHECK (dpop_jkt ~ '^[A-Za-z0-9_-]{43}$'),
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
  used_at timestamptz
);
CREATE INDEX oauth_par_requests_expiry ON oauth_par_requests(expires_at);
