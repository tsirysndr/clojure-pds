-- Every credential/status mutation invalidates previously authenticated OAuth
-- interactions and grants, including changes made by administrative SQL paths.
ALTER TABLE accounts ADD COLUMN oauth_epoch bigint NOT NULL DEFAULT 0 CHECK (oauth_epoch >= 0);
CREATE FUNCTION advance_oauth_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.password_hash, NEW.email, NEW.email_confirmed, NEW.email_auth_factor, NEW.status)
     IS DISTINCT FROM ROW(OLD.password_hash, OLD.email, OLD.email_confirmed, OLD.email_auth_factor, OLD.status) THEN
    NEW.oauth_epoch := GREATEST(OLD.oauth_epoch + 1, NEW.oauth_epoch);
  ELSE
    -- Factor enrollment/removal may explicitly advance this version as well.
    NEW.oauth_epoch := GREATEST(OLD.oauth_epoch, NEW.oauth_epoch);
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER accounts_oauth_epoch BEFORE UPDATE ON accounts
FOR EACH ROW EXECUTE FUNCTION advance_oauth_epoch();

CREATE TABLE oauth_interactions (
    interaction_hash text PRIMARY KEY CHECK (length(interaction_hash) = 43),
    browser_hash text NOT NULL CHECK (length(browser_hash) = 43),
    csrf_nonce text NOT NULL CHECK (length(csrf_nonce) = 43),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    did text REFERENCES accounts(did) ON DELETE CASCADE,
    account_epoch bigint,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    completed_at timestamptz,
    CHECK ((did IS NULL) = (account_epoch IS NULL))
);
CREATE INDEX oauth_interactions_expiry ON oauth_interactions(expires_at);

CREATE TABLE oauth_codes (
    code_hash text PRIMARY KEY CHECK (length(code_hash) = 43),
    did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
    account_epoch bigint NOT NULL,
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    used_at timestamptz
);
CREATE INDEX oauth_codes_expiry ON oauth_codes(expires_at);
