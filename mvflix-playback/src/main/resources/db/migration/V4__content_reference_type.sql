ALTER TABLE playback_session
  RENAME COLUMN asset_id TO content_reference_id;

ALTER TABLE playback_session
  ADD COLUMN content_reference_type VARCHAR(32) NOT NULL DEFAULT 'LIBRARY_ASSET';
