ALTER TABLE account_tokens DROP CONSTRAINT account_tokens_purpose_check;
ALTER TABLE account_tokens ADD CONSTRAINT account_tokens_purpose_check
  CHECK (purpose IN ('confirm-email', 'reset-password', 'delete-account', 'update-email', 'sign-in', 'plc-operation'));
