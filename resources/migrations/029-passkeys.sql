CREATE TABLE account_webauthn_users (
    did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
    user_handle bytea NOT NULL UNIQUE CHECK (octet_length(user_handle) = 32)
);
CREATE TABLE account_passkeys (
    credential_id bytea PRIMARY KEY CHECK (octet_length(credential_id) BETWEEN 1 AND 1023),
    did text NOT NULL REFERENCES account_webauthn_users(did) ON DELETE CASCADE,
    label text NOT NULL CHECK (length(label) BETWEEN 1 AND 64),
    public_key_cose bytea NOT NULL CHECK (octet_length(public_key_cose) BETWEEN 1 AND 4096),
    signature_count bigint NOT NULL CHECK (signature_count BETWEEN 0 AND 4294967295),
    backup_eligible boolean NOT NULL,
    backed_up boolean NOT NULL,
    transports jsonb NOT NULL CHECK (jsonb_typeof(transports) = 'array'),
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    CHECK (NOT backed_up OR backup_eligible)
);
CREATE INDEX account_passkeys_account ON account_passkeys(did);
CREATE TABLE webauthn_challenges (
    challenge_hash text PRIMARY KEY CHECK (length(challenge_hash) = 43),
    browser_hash text NOT NULL CHECK (length(browser_hash) = 43),
    did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
    account_epoch bigint NOT NULL,
    ceremony text NOT NULL CHECK (ceremony IN ('register', 'authenticate')),
    request text NOT NULL CHECK (octet_length(request) <= 65536),
    label text,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    used_at timestamptz
);
CREATE INDEX webauthn_challenges_expiry ON webauthn_challenges(expires_at);
CREATE INDEX webauthn_challenges_account ON webauthn_challenges(did, expires_at) WHERE used_at IS NULL;
