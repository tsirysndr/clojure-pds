-- Public, retry-safe receipts. Queued CID '-' means there was no queued job.
CREATE TABLE plc_reconciliations (
  did text NOT NULL REFERENCES accounts(did) ON DELETE CASCADE,
  local_cid text NOT NULL,
  queued_cid text NOT NULL,
  remote_cid text NOT NULL,
  result jsonb NOT NULL,
  database_role text NOT NULL DEFAULT current_user,
  completed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (did, local_cid, queued_cid, remote_cid)
);
