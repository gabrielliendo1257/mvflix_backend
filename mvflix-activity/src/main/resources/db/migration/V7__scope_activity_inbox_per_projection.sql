ALTER TABLE activity_inbox ADD COLUMN projection_name VARCHAR(64);
UPDATE activity_inbox SET projection_name = 'watch_activity';
ALTER TABLE activity_inbox ALTER COLUMN projection_name SET NOT NULL;
ALTER TABLE activity_inbox DROP CONSTRAINT activity_inbox_pkey;
ALTER TABLE activity_inbox ADD PRIMARY KEY (projection_name, event_id);
