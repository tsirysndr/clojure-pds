CREATE TABLE oauth_permission_set_cache (
    nsid text PRIMARY KEY CHECK (length(nsid) BETWEEN 1 AND 317),
    document bytea CHECK (octet_length(document) <= 1000000),
    did text,
    cid text,
    head text,
    rev text,
    fetched_at bigint,
    next_attempt_at bigint NOT NULL,
    lease_id text CHECK (length(lease_id) = 43),
    lease_until bigint NOT NULL,
    CHECK ((document IS NULL AND did IS NULL AND cid IS NULL AND head IS NULL AND rev IS NULL AND fetched_at IS NULL)
        OR (document IS NOT NULL AND did IS NOT NULL AND cid IS NOT NULL AND head IS NOT NULL AND rev IS NOT NULL AND fetched_at IS NOT NULL))
);
CREATE INDEX oauth_permission_set_cache_eviction ON oauth_permission_set_cache(next_attempt_at);
