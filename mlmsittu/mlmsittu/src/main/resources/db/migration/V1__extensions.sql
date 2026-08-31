-- P0-03 · Extensions and shared migration helpers.
--
-- ltree is needed by the referral hierarchy in Phase 4 (architecture §2.2). It is enabled now,
-- not later, because the GIST index and column type are structural decisions the development
-- plan lists as "must be right from day one".
--
-- pgcrypto supplies gen_random_uuid() for primary key defaults.

CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------------------------
-- Runtime privilege helper.
--
-- Migrations run as the owning role (postgres). The application connects as a separate,
-- non-superuser role so that Gate 1's "audit log is append-only at the DB permission level"
-- is a real database control rather than a coding convention.
--
-- Every migration that creates tables calls this at the end. It is a no-op when the application
-- role has not been created yet, so a developer who skips db/bootstrap/01-app-role.sql still
-- gets a working schema — they just cannot start the app until they run it.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION grant_app_privileges() RETURNS void AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mlmsittu_app') THEN
        RAISE NOTICE 'Role mlmsittu_app does not exist - skipping grants. '
                     'Run db/bootstrap/01-app-role.sql before starting the application.';
        RETURN;
    END IF;

    GRANT USAGE ON SCHEMA public TO mlmsittu_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mlmsittu_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mlmsittu_app;
END;
$$ LANGUAGE plpgsql;

SELECT grant_app_privileges();
