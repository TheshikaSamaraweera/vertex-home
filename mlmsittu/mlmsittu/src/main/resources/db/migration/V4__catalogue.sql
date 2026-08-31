-- P2-01 · Categories and items (architecture §4.1).

CREATE TABLE category (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code       VARCHAR(64)  NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE item (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Normalised to upper case by the service before insert, so a plain UNIQUE constraint
    -- catches "slv-001" colliding with "SLV-001". Storing the normalised form keeps the
    -- constraint usable as an index for lookups too.
    sku           VARCHAR(64)  NOT NULL UNIQUE,

    name          VARCHAR(255) NOT NULL,
    description   VARCHAR(1000),
    category_id   UUID REFERENCES category(id),

    -- Admin-maintained per the architecture decision: no moving-average or FIFO costing.
    unit_cost     NUMERIC(14,2) NOT NULL CHECK (unit_cost >= 0),
    selling_price NUMERIC(14,2) NOT NULL CHECK (selling_price >= 0),

    reorder_level INTEGER      NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_item_category ON item (category_id);

-- Partial index: the default listing filters to active items, which is the hot path.
CREATE INDEX idx_item_active ON item (name) WHERE is_active;

SELECT grant_app_privileges();
