CREATE TABLE authenticator_recoveries (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  previous_version bigint NOT NULL CHECK (previous_version >= 0),
  security_version bigint NOT NULL CHECK (security_version > previous_version),
  reference text NOT NULL CHECK (length(reference) BETWEEN 1 AND 128),
  database_role text NOT NULL DEFAULT current_user,
  removed_factors jsonb NOT NULL,
  completed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (did, previous_version)
);
