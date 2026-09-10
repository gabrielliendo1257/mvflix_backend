ALTER TABLE multipart_upload_sessions
    ALTER COLUMN minio_upload_id TYPE VARCHAR(512)
    USING minio_upload_id::text;
