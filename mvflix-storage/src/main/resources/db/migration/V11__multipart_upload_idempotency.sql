ALTER TABLE multipart_upload_sessions ADD COLUMN idempotency_key VARCHAR(255);
CREATE UNIQUE INDEX uq_multipart_upload_owner_key
    ON multipart_upload_sessions(owner_username, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
