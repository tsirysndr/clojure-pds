CREATE TABLE relay_announcements (
    relay_url text NOT NULL,
    hostname text NOT NULL,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 30),
    lease_id uuid,
    lease_until timestamptz,
    last_success_at timestamptz,
    last_status integer,
    last_error text CHECK (last_error IN ('http-error', 'transport-error')),
    PRIMARY KEY (relay_url, hostname),
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
