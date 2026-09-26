ALTER TABLE records ADD COLUMN repo_rev text;
-- Earlier versions did not keep per-record revisions. Use the current repo
-- revision as a conservative baseline, without changing any signed content.
UPDATE records r SET repo_rev = p.rev FROM repositories p WHERE p.did = r.did;
CREATE INDEX records_by_revision ON records(did, repo_rev COLLATE "C");
