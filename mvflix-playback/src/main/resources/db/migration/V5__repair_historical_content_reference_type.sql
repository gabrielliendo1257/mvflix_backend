UPDATE playback_session
SET content_reference_type = 'MANAGED_OBJECT'
WHERE content_reference_type = 'LIBRARY_ASSET';
