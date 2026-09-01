CREATE TABLE activity_feed (
  activity_id UUID PRIMARY KEY,
  audience_id VARCHAR(255) NOT NULL,
  actor_id VARCHAR(255) NOT NULL,
  correlation_id UUID NOT NULL,
  activity_type VARCHAR(80) NOT NULL,
  status VARCHAR(32) NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  last_occurred_at TIMESTAMPTZ NOT NULL,
  last_event_id UUID NOT NULL,
  last_event_type VARCHAR(120) NOT NULL,
  file_name VARCHAR(1024),
  catalog_item_id BIGINT,
  failure_code VARCHAR(120),
  UNIQUE (audience_id, correlation_id)
);

CREATE INDEX activity_feed_cursor_idx
  ON activity_feed(audience_id, last_occurred_at DESC, last_event_id DESC);
