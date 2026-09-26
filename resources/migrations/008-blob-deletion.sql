CREATE TABLE blob_delete_jobs (
    id bigserial PRIMARY KEY,
    object_bucket text NOT NULL,
    object_key text NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'failed')),
    available_at timestamptz NOT NULL DEFAULT now(),
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(object_bucket, object_key)
);
