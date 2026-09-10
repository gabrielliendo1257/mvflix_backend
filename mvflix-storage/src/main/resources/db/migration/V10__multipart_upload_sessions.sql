CREATE TABLE multipart_upload_sessions (
    upload_id VARCHAR(64) PRIMARY KEY,
    minio_upload_id UUID NOT NULL UNIQUE,
    owner_username VARCHAR(255) NOT NULL,
    bucket_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(255) NOT NULL UNIQUE,
    total_bytes BIGINT NOT NULL CHECK (total_bytes > 0),
    content_type VARCHAR(128) NOT NULL,
    part_size_bytes BIGINT NOT NULL CHECK (part_size_bytes > 0),
    total_parts INTEGER NOT NULL CHECK (total_parts > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_multipart_sessions_expiry
    ON multipart_upload_sessions(status, expires_at);

CREATE INDEX idx_multipart_sessions_owner
    ON multipart_upload_sessions(owner_username, created_at DESC);
