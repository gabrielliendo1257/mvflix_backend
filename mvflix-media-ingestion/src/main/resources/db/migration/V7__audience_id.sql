ALTER TABLE media_ingestions ADD COLUMN audience_id VARCHAR(255);
UPDATE media_ingestions SET audience_id = actor_id WHERE audience_id IS NULL;
ALTER TABLE media_ingestions ALTER COLUMN audience_id SET NOT NULL;
