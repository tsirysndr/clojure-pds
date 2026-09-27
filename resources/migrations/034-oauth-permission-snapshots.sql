ALTER TABLE oauth_par_requests ADD COLUMN permission_sets jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(permission_sets) = 'object');
-- NULL is reserved for access tokens minted before this migration (direct and
-- transitional scopes only). Refresh tokens carry no resource authority.
ALTER TABLE oauth_tokens ADD COLUMN permissions jsonb
    CHECK (permissions IS NULL OR (kind = 'access' AND jsonb_typeof(permissions) = 'array'));
