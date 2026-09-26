-- Empty blobs are valid in the protocol. Application Lexicons can impose
-- additional size constraints when a record references them.
ALTER TABLE blobs DROP CONSTRAINT blobs_size_positive;
ALTER TABLE blobs ADD CONSTRAINT blobs_size_nonnegative CHECK (size >= 0);
-- The migration runner reindexes existing records, including imported empty
-- blob references that earlier versions skipped. Signed records stay unchanged.
