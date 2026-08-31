-- ==============================================================================================
-- ONE-TIME BOOTSTRAP · run as a superuser, before `./gradlew test` for the first time.
--
--   "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -U postgres -d postgres ^
--       -f db\bootstrap\02-test-database.sql
--
-- Why a separate database:
--
-- The Phase 3 concurrency tests deliberately drive stock to zero, race ten threads for the last
-- unit, and run the whole thing a hundred times over. Pointing that at the development database
-- would destroy your seed data every time you ran the build, and a test that damages the thing
-- you were about to demo is worse than no test.
--
-- Testcontainers would normally handle this, but it needs Docker, which is not installed here.
-- A second local database gives the same isolation. Phase 8 swaps this for Testcontainers in CI.
-- ==============================================================================================

SELECT 'CREATE DATABASE mlmsitty_test'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'mlmsitty_test')\gexec

\c mlmsitty_test

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mlmsittu_app') THEN
        RAISE EXCEPTION 'Role mlmsittu_app does not exist. Run 01-app-role.sql first.';
    END IF;
END $$;

GRANT CONNECT ON DATABASE mlmsitty_test TO mlmsittu_app;
GRANT USAGE ON SCHEMA public TO mlmsittu_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mlmsittu_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mlmsittu_app;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mlmsittu_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO mlmsittu_app;

-- Same rule as the development database: the application never issues DDL.
REVOKE CREATE ON SCHEMA public FROM mlmsittu_app;

\echo 'Test database ready. Flyway migrates it automatically on the first test run.'
