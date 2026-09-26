CREATE TABLE sessions (
    id uuid PRIMARY KEY,
    did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
    revoked boolean NOT NULL DEFAULT false,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sessions_did ON sessions(did);
CREATE TABLE refresh_tokens (
    token_hash text PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    used boolean NOT NULL DEFAULT false,
    expires_at timestamptz NOT NULL
);
CREATE TABLE account_tokens (
    token_hash text PRIMARY KEY,
    did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
    purpose text NOT NULL CHECK (purpose IN ('confirm-email', 'reset-password', 'delete-account')),
    email text NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX account_tokens_did_purpose ON account_tokens(did, purpose);
