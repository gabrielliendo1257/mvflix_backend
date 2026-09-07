CREATE TABLE playback_session (
  id UUID PRIMARY KEY,
  viewer_id VARCHAR(255) NOT NULL,
  catalog_item_id BIGINT NOT NULL,
  asset_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  last_sequence BIGINT NOT NULL DEFAULT 0,
  last_position_seconds BIGINT,
  last_duration_seconds BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX playback_session_viewer_idx
  ON playback_session(viewer_id, started_at DESC);

CREATE TABLE watch_progress (
  viewer_id VARCHAR(255) NOT NULL,
  catalog_item_id BIGINT NOT NULL,
  position_seconds BIGINT,
  duration_seconds BIGINT,
  completed BOOLEAN NOT NULL DEFAULT FALSE,
  last_session_id UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (viewer_id, catalog_item_id)
);
