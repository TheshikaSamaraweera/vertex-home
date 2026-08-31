-- Regression fix: the append-only controls on audit_log and stock_movement were being undone.
--
-- WHAT WENT WRONG
--
-- V1 defined grant_app_privileges() as a blanket
--     GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES ... TO mlmsittu_app
-- and every migration calls it at the end. V3 revoked UPDATE/DELETE on audit_log and V5 did the
-- same for stock_movement — but V4, V6 and V7 each called the helper again afterwards, and each
-- call handed the write permissions straight back.
--
-- The result: by the end of Phase 2 the application role could delete its own audit trail and its
-- own stock ledger. Both controls had passed their gate and both were silently gone. This is
-- exactly the regression the cumulative-gate rule in the development plan exists to catch.
--
-- THE FIX
--
-- Ordering was the wrong thing to rely on. A migration author should not have to remember to
-- re-revoke after calling a shared helper, because eventually one of them will not. The helper
-- itself now knows which tables are append-only and finishes by taking write access back, so the
-- restriction is re-asserted every time privileges are touched rather than being a one-off.
--
-- Adding an append-only table in future means adding its name to the array below and nothing else.

CREATE OR REPLACE FUNCTION grant_app_privileges() RETURNS void AS $$
DECLARE
    -- Tables the application may read and insert, but never update or delete.
    append_only CONSTANT TEXT[] := ARRAY['audit_log', 'stock_movement'];
    target RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mlmsittu_app') THEN
        RAISE NOTICE 'Role mlmsittu_app does not exist - skipping grants. '
                     'Run db/bootstrap/01-app-role.sql before starting the application.';
        RETURN;
    END IF;

    GRANT USAGE ON SCHEMA public TO mlmsittu_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mlmsittu_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mlmsittu_app;

    -- Now take it back where it does not belong. Partitions are matched through pg_inherits, so
    -- audit_log_2026_08 is covered without being named — a partition left writable would let a
    -- caller delete history straight out of the child table.
    FOR target IN
        SELECT child.relname AS name
        FROM pg_class child
        JOIN pg_namespace ns ON ns.oid = child.relnamespace
        WHERE ns.nspname = 'public'
          AND child.relkind IN ('r', 'p')
          AND (
                child.relname = ANY (append_only)
                OR EXISTS (
                    SELECT 1
                    FROM pg_inherits inh
                    JOIN pg_class parent ON parent.oid = inh.inhparent
                    WHERE inh.inhrelid = child.oid
                      AND parent.relname = ANY (append_only)
                )
          )
    LOOP
        EXECUTE format(
            'REVOKE UPDATE, DELETE, TRUNCATE ON public.%I FROM mlmsittu_app', target.name);
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- Re-assert correct privileges immediately.
SELECT grant_app_privileges();
