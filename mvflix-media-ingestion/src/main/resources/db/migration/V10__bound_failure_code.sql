UPDATE media_ingestions
SET failure_detail = COALESCE(failure_detail, failure_code),
    failure_code = LEFT(failure_code, 120)
WHERE length(failure_code) > 120;

ALTER TABLE media_ingestions
  ALTER COLUMN failure_code TYPE VARCHAR(120);
