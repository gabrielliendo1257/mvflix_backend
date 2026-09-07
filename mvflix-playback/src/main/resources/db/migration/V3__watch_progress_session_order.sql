ALTER TABLE watch_progress
  ADD COLUMN last_session_started_at TIMESTAMPTZ NOT NULL DEFAULT TIMESTAMPTZ 'epoch';

UPDATE watch_progress progress
SET last_session_started_at = session.started_at
FROM playback_session session
WHERE progress.last_session_id = session.id;
