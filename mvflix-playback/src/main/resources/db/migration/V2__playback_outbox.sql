CREATE TABLE playback_outbox (
  event_id UUID PRIMARY KEY,
  event_type VARCHAR(100) NOT NULL,
  aggregate_id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  payload JSONB NOT NULL,
  published_at TIMESTAMPTZ,
  attempts INTEGER NOT NULL DEFAULT 0,
  claimed_until TIMESTAMPTZ,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_error VARCHAR(2000)
);
CREATE INDEX playback_outbox_pending_idx ON playback_outbox(published_at, next_attempt_at);
