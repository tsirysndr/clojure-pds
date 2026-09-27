CREATE TABLE master_key_state (
  id boolean PRIMARY KEY DEFAULT true CHECK (id),
  fingerprint text NOT NULL CHECK (length(fingerprint) = 43),
  generation bigint NOT NULL DEFAULT 0 CHECK (generation >= 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE master_key_rotations (
  previous_fingerprint text PRIMARY KEY CHECK (length(previous_fingerprint) = 43),
  fingerprint text NOT NULL UNIQUE CHECK (length(fingerprint) = 43),
  generation bigint NOT NULL UNIQUE CHECK (generation > 0),
  counts jsonb NOT NULL,
  completed_at timestamptz NOT NULL DEFAULT now()
);
