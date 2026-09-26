CREATE TABLE accounts (
    did text PRIMARY KEY,
    handle text NOT NULL UNIQUE,
    email text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    email_confirmed boolean NOT NULL DEFAULT false,
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'deactivated', 'taken_down')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE repo_blocks (
    cid text PRIMARY KEY,
    content bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE repositories (
    did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
    head text REFERENCES repo_blocks(cid),
    rev text,
    signing_key bytea NOT NULL,
    public_key bytea NOT NULL
);

CREATE TABLE records (
    did text NOT NULL REFERENCES repositories(did) ON DELETE CASCADE,
    collection text NOT NULL,
    rkey text NOT NULL,
    cid text NOT NULL REFERENCES repo_blocks(cid),
    PRIMARY KEY (did, collection, rkey)
);

CREATE TABLE blobs (
    did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
    cid text NOT NULL,
    mime_type text NOT NULL,
    content bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (did, cid)
);
