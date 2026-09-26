ALTER TABLE accounts ADD COLUMN status_before_takedown text NOT NULL DEFAULT 'active'
  CHECK (status_before_takedown IN ('active', 'deactivated'));
ALTER TABLE accounts ADD COLUMN takedown_ref text;
