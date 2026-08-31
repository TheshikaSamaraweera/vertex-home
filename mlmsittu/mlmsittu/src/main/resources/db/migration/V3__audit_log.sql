-- P1-06 · Append-only audit log, partitioned monthly (architecture §8.2).
--
-- A partitioned table's primary key must contain the partition key, hence PRIMARY KEY
-- (id, created_at) rather than (id) alone. The sequence behind id is still global, so ids
-- remain unique across every partition.

CREATE TABLE audit_log (
    id          BIGSERIAL,
    actor_id    UUID,
    action      VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id   UUID,
    before      JSONB,
    after       JSONB,
    ip          INET,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_audit_log_actor  ON audit_log (actor_id, created_at DESC);
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_log_action ON audit_log (action, created_at DESC);

-- Three years of monthly partitions. An insert with a created_at outside every partition fails
-- loudly rather than landing somewhere wrong, which is the behaviour we want: a missing partition
-- is an operational alarm. Phase 7 adds a scheduled job that rolls new partitions forward.
DO $$
DECLARE
    month_start DATE := DATE '2026-01-01';
    month_end   DATE := DATE '2029-01-01';
    partition_name TEXT;
BEGIN
    WHILE month_start < month_end LOOP
        partition_name := 'audit_log_' || to_char(month_start, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            partition_name,
            month_start,
            month_start + INTERVAL '1 month');
        month_start := month_start + INTERVAL '1 month';
    END LOOP;
END $$;

SELECT grant_app_privileges();

-- ---------------------------------------------------------------------------------------------
-- Append-only, enforced by the database rather than by convention.
--
-- The application role may read and insert. It may not update, delete or truncate — not through
-- the parent table and not through any individual partition. Gate 1 verifies this by running
-- DELETE FROM audit_log as mlmsittu_app and confirming a permission error.
-- ---------------------------------------------------------------------------------------------

DO $$
DECLARE
    partition RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mlmsittu_app') THEN
        RAISE NOTICE 'Role mlmsittu_app does not exist - skipping audit_log restrictions.';
        RETURN;
    END IF;

    REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM mlmsittu_app;
    GRANT SELECT, INSERT ON audit_log TO mlmsittu_app;

    FOR partition IN
        SELECT child.relname AS name
        FROM pg_inherits
        JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
        JOIN pg_class child  ON child.oid  = pg_inherits.inhrelid
        WHERE parent.relname = 'audit_log'
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM mlmsittu_app', partition.name);
        EXECUTE format('GRANT SELECT, INSERT ON %I TO mlmsittu_app', partition.name);
    END LOOP;
END $$;
