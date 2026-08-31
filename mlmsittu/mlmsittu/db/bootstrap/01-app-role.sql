-- ==============================================================================================
-- ONE-TIME BOOTSTRAP · run as a superuser, before the first application start.
--
-- FOR A NATIVE PostgreSQL ON A DEVELOPMENT MACHINE ONLY.
--
-- Docker uses 01-app-role.sh instead, which takes the password from DB_PASSWORD. This file
-- carries a literal, which is fine for a laptop database holding nothing and reachable from
-- nowhere, and is exactly why it is not what a server runs: the value below is published in this
-- repository, so a production deployment using it would have no application-role password at all.
--
--   "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d mlmsitty ^
--       -f db\bootstrap\01-app-role.sql
--
-- Why a second role exists at all:
--
-- Flyway migrates as the owner (postgres) because it creates tables, extensions and partitions.
-- The application connects as mlmsittu_app, which is deliberately weaker — it has no DDL rights
-- and, critically, no UPDATE or DELETE on audit_log. That is what makes "the audit log is
-- append-only" a database guarantee instead of a promise about our own code. A superuser
-- connection would silently ignore every one of those restrictions.
--
-- Production note: provision this role out of band with a real secret from the secret manager.
-- The password below is a local development convenience only.
-- ==============================================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mlmsittu_app') THEN
        CREATE ROLE mlmsittu_app LOGIN PASSWORD 'mlmsittu_app';
        RAISE NOTICE 'Created role mlmsittu_app.';
    ELSE
        RAISE NOTICE 'Role mlmsittu_app already exists - leaving it alone.';
    END IF;
END $$;

GRANT CONNECT ON DATABASE mlmsitty TO mlmsittu_app;
GRANT USAGE ON SCHEMA public TO mlmsittu_app;

-- Objects that already exist.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mlmsittu_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mlmsittu_app;

-- Objects Flyway creates from now on. Without this, every new migration would need explicit
-- grants and one forgotten line would break the app at runtime rather than at migration time.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mlmsittu_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO mlmsittu_app;

-- The app must never issue DDL. Hibernate runs in validate mode; Flyway owns the schema.
REVOKE CREATE ON SCHEMA public FROM mlmsittu_app;
