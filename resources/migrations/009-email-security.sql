ALTER TABLE accounts ADD COLUMN email_auth_factor boolean NOT NULL DEFAULT false;
ALTER TABLE accounts ADD CONSTRAINT accounts_email_auth_factor_confirmed
    CHECK (NOT email_auth_factor OR email_confirmed);
ALTER TABLE account_tokens DROP CONSTRAINT account_tokens_purpose_check;
ALTER TABLE account_tokens ADD CONSTRAINT account_tokens_purpose_check
    CHECK (purpose IN ('confirm-email', 'reset-password', 'delete-account', 'update-email', 'sign-in'));
