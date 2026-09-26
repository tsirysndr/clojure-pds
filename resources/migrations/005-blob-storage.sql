ALTER TABLE blobs ADD COLUMN size bigint;
UPDATE blobs SET size = octet_length(content);
ALTER TABLE blobs ALTER COLUMN size SET NOT NULL;
ALTER TABLE blobs ALTER COLUMN content DROP NOT NULL;
ALTER TABLE blobs ADD COLUMN storage_backend text NOT NULL DEFAULT 'postgres';
ALTER TABLE blobs ADD COLUMN object_key text;
ALTER TABLE blobs ADD COLUMN object_bucket text;
ALTER TABLE blobs ADD CONSTRAINT blobs_size_positive CHECK (size > 0);
ALTER TABLE blobs ADD CONSTRAINT blobs_storage_consistent CHECK (
    (storage_backend = 'postgres' AND content IS NOT NULL AND size = octet_length(content)
        AND object_key IS NULL AND object_bucket IS NULL)
    OR
    (storage_backend = 's3' AND content IS NULL AND object_key IS NOT NULL
        AND object_bucket IS NOT NULL)
);
