CREATE TABLE browser_sessions (
    token_hash text PRIMARY KEY CHECK (length(token_hash) = 43),
    csrf_nonce text NOT NULL CHECK (length(csrf_nonce) = 43),
    did text REFERENCES accounts(did) ON DELETE CASCADE,
    account_epoch bigint,
    auth_method text CHECK (auth_method IN ('password', 'passkey')),
    authenticated_at timestamptz,
    passkey_request text,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    CHECK ((did IS NULL) = (account_epoch IS NULL)),
    CHECK ((did IS NULL) = (auth_method IS NULL)),
    CHECK (authenticated_at IS NULL OR did IS NOT NULL)
);
CREATE INDEX browser_sessions_expiry ON browser_sessions(expires_at);
