-- ---------- 2. Create databases idempotently ----------
SELECT format('CREATE DATABASE %I OWNER db_migrator', database_name)
FROM (VALUES
  ('test'),
  ('mvflix_uploads_db'),
  ('mvflix_users_db'),
  ('mvflix_movies_db'),
  ('mvflix_authorized_db'),
  ('mvflix_activity_db'),
  ('mvflix_playback_db'),
  ('mvflix_media_ingestion_db'),
  ('mvflix_app')
) AS databases(database_name)
WHERE NOT EXISTS (
  SELECT 1 FROM pg_database WHERE datname = database_name
);
\gexec

ALTER DATABASE test OWNER TO db_migrator;
ALTER DATABASE mvflix_uploads_db OWNER TO db_migrator;
ALTER DATABASE mvflix_users_db OWNER TO db_migrator;
ALTER DATABASE mvflix_movies_db OWNER TO db_migrator;
ALTER DATABASE mvflix_authorized_db OWNER TO db_migrator;
ALTER DATABASE mvflix_activity_db OWNER TO db_migrator;
ALTER DATABASE mvflix_playback_db OWNER TO db_migrator;
ALTER DATABASE mvflix_media_ingestion_db OWNER TO db_migrator;
ALTER DATABASE mvflix_app OWNER TO db_migrator;
