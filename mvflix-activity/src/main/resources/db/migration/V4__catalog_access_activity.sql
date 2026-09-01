ALTER TABLE activity_feed
  ADD COLUMN activity_key VARCHAR(255),
  ADD COLUMN category VARCHAR(64),
  ADD COLUMN severity VARCHAR(32),
  ADD COLUMN resource_type VARCHAR(64),
  ADD COLUMN resource_id VARCHAR(255),
  ADD COLUMN resource_title VARCHAR(1024),
  ADD COLUMN details JSONB,
  ADD COLUMN received_at TIMESTAMPTZ;
