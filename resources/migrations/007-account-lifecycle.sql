ALTER TABLE accounts DROP CONSTRAINT accounts_status_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_status_check
    CHECK (status IN ('active', 'deactivated', 'taken_down', 'deleted'));
ALTER TABLE accounts ADD COLUMN delete_after text;
ALTER TABLE accounts ALTER COLUMN email DROP NOT NULL;
ALTER TABLE accounts ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE repo_events ALTER COLUMN rev DROP NOT NULL;
ALTER TABLE repo_events ALTER COLUMN commit_cid DROP NOT NULL;
ALTER TABLE repo_events ADD COLUMN event_type text NOT NULL DEFAULT 'commit';
ALTER TABLE repo_events ADD COLUMN payload bytea;
