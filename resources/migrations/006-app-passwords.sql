CREATE TABLE app_passwords (
    id uuid PRIMARY KEY,
    did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
    name text NOT NULL,
    password_digest text NOT NULL,
    privileged boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (did, name),
    UNIQUE (did, password_digest),
    UNIQUE (id, did)
);
ALTER TABLE sessions ADD COLUMN app_password_id uuid;
ALTER TABLE sessions ADD CONSTRAINT sessions_app_password_owner
    FOREIGN KEY (app_password_id, did) REFERENCES app_passwords(id, did) ON DELETE CASCADE;
CREATE INDEX sessions_app_password ON sessions(app_password_id);
