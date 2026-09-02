UPDATE activity_feed
SET activity_key = 'ingestion:' || correlation_id
WHERE activity_key IS NULL;

ALTER TABLE activity_feed
  DROP CONSTRAINT activity_feed_audience_id_correlation_id_key,
  ALTER COLUMN activity_key SET NOT NULL;

CREATE UNIQUE INDEX activity_feed_identity_idx
  ON activity_feed(audience_id, activity_key);
