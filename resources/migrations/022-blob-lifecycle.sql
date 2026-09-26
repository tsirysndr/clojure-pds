-- Give pre-existing unreferenced uploads a full grace period on upgrade.
ALTER TABLE blobs ADD COLUMN uploaded_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX blobs_by_upload_time ON blobs(uploaded_at);
ALTER TABLE blob_delete_jobs ADD COLUMN did text;
ALTER TABLE blob_delete_jobs ADD COLUMN cid text;
-- Legacy jobs came from permanently deleted accounts; their DID cannot be reused.
ALTER TABLE blob_delete_jobs ADD CONSTRAINT blob_delete_job_owner_pair
  CHECK ((did IS NULL) = (cid IS NULL));
