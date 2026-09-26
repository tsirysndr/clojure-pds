CREATE TABLE oauth_sessions (
    session_id text PRIMARY KEY CHECK (length(session_id) = 43),
    code_hash text NOT NULL UNIQUE CHECK (length(code_hash) = 43),
    did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
    account_epoch bigint NOT NULL,
    client_id text NOT NULL,
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    revoked_at timestamptz,
    revoke_reason text
);
CREATE INDEX oauth_sessions_account ON oauth_sessions(did);
CREATE INDEX oauth_sessions_expiry ON oauth_sessions(expires_at);
CREATE TABLE oauth_tokens (
    token_hash text PRIMARY KEY CHECK (length(token_hash) = 43),
    session_id text NOT NULL REFERENCES oauth_sessions(session_id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('access', 'refresh')),
    scope text NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    used_at timestamptz,
    CHECK (kind = 'refresh' OR used_at IS NULL)
);
CREATE INDEX oauth_tokens_session ON oauth_tokens(session_id);
CREATE INDEX oauth_tokens_expiry ON oauth_tokens(expires_at);
