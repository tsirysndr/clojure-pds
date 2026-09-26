CREATE TABLE account_totp (
    did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
    sealed_secret bytea NOT NULL,
    confirmed boolean NOT NULL DEFAULT false,
    enrollment_epoch bigint NOT NULL,
    enrollment_expires_at timestamptz NOT NULL,
    last_step bigint,
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    attempt_window timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account_recovery_codes (
    did text NOT NULL REFERENCES account_totp(did) ON DELETE CASCADE,
    code_hash text NOT NULL CHECK (length(code_hash) = 43),
    PRIMARY KEY (did, code_hash)
);
