-- Share the durable identity queue and its per-account lease with handle changes.
ALTER TABLE handle_updates ADD COLUMN operation_kind text NOT NULL DEFAULT 'handle'
  CHECK (operation_kind IN ('handle', 'submit'));
