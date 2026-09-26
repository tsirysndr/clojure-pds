CREATE TABLE repo_block_owners (
  did text NOT NULL REFERENCES repositories(did) ON DELETE CASCADE,
  cid text NOT NULL REFERENCES repo_blocks(cid),
  PRIMARY KEY(did, cid)
);
CREATE INDEX repo_block_owners_cid ON repo_block_owners(cid);
