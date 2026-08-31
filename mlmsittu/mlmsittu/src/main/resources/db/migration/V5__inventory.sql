-- P2-03, P2-04 · Locations, the stock ledger, and its projection (architecture §4.1).
--
-- Multi-location from day one with a single seeded default. Retrofitting location into
-- stock_level after launch would rewrite every stock query in the system.

CREATE TABLE location (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code       VARCHAR(64)  NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    address    VARCHAR(500),
    is_default BOOLEAN      NOT NULL DEFAULT false,
    is_active  BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- At most one default location, enforced by the database rather than by hoping.
-- Every row matching the predicate has is_default = true, so uniqueness on that column
-- permits exactly one such row.
CREATE UNIQUE INDEX idx_location_single_default ON location (is_default) WHERE is_default;

INSERT INTO location (code, name, is_default) VALUES ('MAIN', 'Main Warehouse', true);

-- ---------------------------------------------------------------------------------------------
-- stock_movement is the source of truth. Append-only, never corrected in place — a mistake is
-- fixed by posting a compensating movement, so the history of what was believed and when
-- survives. stock_level below is a projection that can be rebuilt from this table alone.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE stock_movement (
    id             BIGSERIAL PRIMARY KEY,
    item_id        UUID NOT NULL REFERENCES item(id),
    location_id    UUID NOT NULL REFERENCES location(id),

    -- Signed. A zero-delta movement carries no information and is almost always a bug upstream.
    qty_delta      INTEGER NOT NULL CHECK (qty_delta <> 0),

    movement_type  VARCHAR(24) NOT NULL,
    reference_type VARCHAR(32),
    reference_id   UUID,

    -- Required for adjustments (P2-10); null for movements whose reference explains them.
    reason         VARCHAR(64),
    note           VARCHAR(500),

    created_by     UUID NOT NULL REFERENCES app_user(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_movement_type CHECK (movement_type IN
        ('OPENING_BALANCE', 'RECEIPT', 'ADJUSTMENT', 'FULFILMENT', 'RETURN', 'RECONCILIATION'))
);

-- Covers the replay query, which groups by (item, location) and sums.
CREATE INDEX idx_movement_item_location ON stock_movement (item_id, location_id, id);
CREATE INDEX idx_movement_reference ON stock_movement (reference_type, reference_id);
CREATE INDEX idx_movement_created_at ON stock_movement (created_at DESC);

-- ---------------------------------------------------------------------------------------------
-- The projection. Written only by StockLedgerService, which an ArchUnit rule enforces at build
-- time; the CHECK constraints below are the runtime backstop for anything that gets past it.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE stock_level (
    item_id     UUID NOT NULL REFERENCES item(id),
    location_id UUID NOT NULL REFERENCES location(id),
    on_hand     INTEGER NOT NULL DEFAULT 0,
    reserved    INTEGER NOT NULL DEFAULT 0,
    version     BIGINT  NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (item_id, location_id),

    CONSTRAINT chk_stock_non_negative CHECK (on_hand >= 0 AND reserved >= 0),

    -- Reservation does not decrement on_hand (architecture §4.5), so reserved stock is always a
    -- subset of stock on hand. Without this, an adjustment could quietly strand reservations
    -- that can never be fulfilled.
    CONSTRAINT chk_reserved_within_on_hand CHECK (reserved <= on_hand)
);

SELECT grant_app_privileges();

-- ---------------------------------------------------------------------------------------------
-- Append-only at the permission level, exactly as audit_log is. A ledger the application can
-- rewrite is not a ledger, and "we only ever insert" is a claim about code that changes weekly.
-- ---------------------------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mlmsittu_app') THEN
        RAISE NOTICE 'Role mlmsittu_app does not exist - skipping stock_movement restrictions.';
        RETURN;
    END IF;

    REVOKE UPDATE, DELETE, TRUNCATE ON stock_movement FROM mlmsittu_app;
    GRANT SELECT, INSERT ON stock_movement TO mlmsittu_app;
END $$;
