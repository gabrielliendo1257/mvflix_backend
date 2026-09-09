CREATE UNIQUE INDEX IF NOT EXISTS ux_media_ingestions_storage_id
    ON media_ingestions(storage_id)
    WHERE storage_id IS NOT NULL;
