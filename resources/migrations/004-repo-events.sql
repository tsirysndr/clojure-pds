CREATE TABLE repo_events (
    seq bigserial PRIMARY KEY,
    did text NOT NULL,
    rev text NOT NULL,
    commit_cid text NOT NULL REFERENCES repo_blocks(cid),
    created_at timestamptz NOT NULL DEFAULT now()
);
