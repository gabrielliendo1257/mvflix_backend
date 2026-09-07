CREATE UNIQUE INDEX playback_session_active_content_idx
  ON playback_session(viewer_id, catalog_item_id)
  WHERE status = 'ACTIVE';
