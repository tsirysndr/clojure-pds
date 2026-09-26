CREATE TABLE record_blob_refs (
    did text NOT NULL,
    collection text NOT NULL,
    rkey text NOT NULL,
    cid text NOT NULL,
    PRIMARY KEY (did, collection, rkey, cid),
    FOREIGN KEY (did, collection, rkey) REFERENCES records(did, collection, rkey) ON DELETE CASCADE
);
-- Missing blobs intentionally have no corresponding row in blobs yet.
CREATE INDEX record_blob_refs_by_cid ON record_blob_refs(did, cid);
