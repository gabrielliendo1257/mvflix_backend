-- ============================================================
-- Apply permissions for each database
-- ============================================================

------------------------------------------------------------
-- Setup for project_db
------------------------------------------------------------
\connect mvflix_uploads_db

-- Remove unsafe default privileges
REVOKE ALL ON DATABASE mvflix_uploads_db FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Prisma requires the db_migrator to own the schema
ALTER SCHEMA public OWNER TO db_migrator;

-- db_Migrator full control
GRANT USAGE, CREATE ON SCHEMA public TO db_migrator;

-- Read/Write application user
GRANT USAGE ON SCHEMA public TO db_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO db_rw;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO db_rw;

-- Readonly user
GRANT USAGE ON SCHEMA public TO db_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO db_ro;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO db_ro;

-- Default privileges for FUTURE objects created by db_migrator
ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO db_rw;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO db_ro;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO db_rw;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO db_ro;

-- Allow connections
GRANT CONNECT ON DATABASE mvflix_uploads_db TO db_migrator, db_rw, db_ro;


------------------------------------------------------------
-- Setup for mvflix_users_db
------------------------------------------------------------
\connect mvflix_users_db

-- Remove unsafe default privileges
REVOKE ALL ON DATABASE mvflix_users_db FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Prisma requires the db_migrator to own the schema
ALTER SCHEMA public OWNER TO db_migrator;

-- db_Migrator full control
GRANT USAGE, CREATE ON SCHEMA public TO db_migrator;

-- Read/Write application user
GRANT USAGE ON SCHEMA public TO db_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO db_rw;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO db_rw;

-- Readonly user
GRANT USAGE ON SCHEMA public TO db_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO db_ro;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO db_ro;

-- Default privileges for FUTURE objects created by db_migrator
ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO db_rw;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO db_ro;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO db_rw;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO db_ro;

-- Allow connections
GRANT CONNECT ON DATABASE mvflix_users_db TO db_migrator, db_rw, db_ro;

-------------------------------
-- Setup for test database
-------------------------------
\connect test

REVOKE ALL ON DATABASE test FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

ALTER SCHEMA public OWNER TO db_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO db_migrator;

GRANT USAGE ON SCHEMA public TO db_rw, db_ro;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO db_rw;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO db_rw;

GRANT SELECT ON ALL TABLES IN SCHEMA public TO db_ro;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO db_ro;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO db_rw;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO db_ro;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO db_rw;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO db_ro;

GRANT CONNECT ON DATABASE test TO db_migrator, db_rw, db_ro;

------------------------------------------------------------
-- Setup for mvflix_authorized_db
------------------------------------------------------------
\connect mvflix_authorized_db

REVOKE ALL ON DATABASE mvflix_authorized_db FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

ALTER SCHEMA public OWNER TO db_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO db_migrator;

GRANT USAGE ON SCHEMA public TO db_rw, db_ro;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO db_rw;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO db_rw;

GRANT SELECT ON ALL TABLES IN SCHEMA public TO db_ro;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO db_ro;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO db_rw;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO db_ro;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO db_rw;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO db_ro;

GRANT CONNECT ON DATABASE mvflix_authorized_db TO db_migrator, db_rw, db_ro;

------------------------------------------------------------
-- Setup for mvflix_movies_db (servicio futuro)
------------------------------------------------------------
\connect mvflix_movies_db

REVOKE ALL ON DATABASE mvflix_movies_db FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

ALTER SCHEMA public OWNER TO db_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO db_migrator;

GRANT USAGE ON SCHEMA public TO db_rw, db_ro;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO db_rw;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO db_ro;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO db_rw;

ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO db_ro;

GRANT CONNECT ON DATABASE mvflix_movies_db TO db_migrator, db_rw, db_ro;

------------------------------------------------------------
-- Setup for mvflix_activity_db
------------------------------------------------------------
\connect mvflix_activity_db
REVOKE ALL ON DATABASE mvflix_activity_db FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO db_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO db_migrator;
GRANT USAGE ON SCHEMA public TO db_rw, db_ro;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO db_rw;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO db_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO db_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public GRANT SELECT ON TABLES TO db_ro;
GRANT CONNECT ON DATABASE mvflix_activity_db TO db_migrator, db_rw, db_ro;

------------------------------------------------------------
-- Setup for mvflix_playback_db
------------------------------------------------------------
\connect mvflix_playback_db

REVOKE ALL ON DATABASE mvflix_playback_db FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO db_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO db_migrator;
GRANT USAGE ON SCHEMA public TO db_rw, db_ro;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO db_rw;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO db_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO db_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO db_ro;
GRANT CONNECT ON DATABASE mvflix_playback_db TO db_migrator, db_rw, db_ro;

------------------------------------------------------------
-- Setup for mvflix_media_ingestion_db
------------------------------------------------------------
\connect mvflix_media_ingestion_db
REVOKE ALL ON DATABASE mvflix_media_ingestion_db FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO db_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO db_migrator;
GRANT USAGE ON SCHEMA public TO db_rw, db_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO db_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public GRANT SELECT ON TABLES TO db_ro;
GRANT CONNECT ON DATABASE mvflix_media_ingestion_db TO db_migrator, db_rw, db_ro;

------------------------------------------------------------
-- Setup for bff-mvflix-web
------------------------------------------------------------
\connect mvflix_app
REVOKE ALL ON DATABASE mvflix_app FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO db_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO db_migrator;
GRANT USAGE ON SCHEMA public TO db_rw, db_ro;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO db_rw;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO db_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO db_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE db_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO db_ro;
GRANT CONNECT ON DATABASE mvflix_app TO db_migrator, db_rw, db_ro;
