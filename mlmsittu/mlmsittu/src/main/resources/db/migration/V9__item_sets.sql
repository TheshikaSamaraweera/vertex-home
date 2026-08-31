-- P3-01 · Item sets — virtual composition (architecture §4.2).
--
-- A set consumes nothing when it is created. It is a way of selling several items together, and
-- the components only leave stock when the set is released. Availability is therefore computed on
-- read from the scarcest component, never stored.

CREATE TABLE item_set (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(1000),

    -- Independent of the sum of its components, deliberately. The delta between set_price and
    -- the component total is the margin the admin UI surfaces.
    set_price   NUMERIC(14,2) NOT NULL CHECK (set_price >= 0),

    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE item_set_line (
    -- Surrogate key, with the real uniqueness expressed as a constraint below.
    --
    -- A composite (set_id, item_id) primary key reads better but fights the ORM: the parent owns
    -- set_id through its @JoinColumn, so the column cannot be insertable on the child, and a
    -- non-insertable key column is written as NULL. The other line tables here (purchase order,
    -- goods receipt, reservation) all use a surrogate for the same reason — being consistent
    -- matters more than the tidier key.
    id       UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    set_id   UUID    NOT NULL REFERENCES item_set(id) ON DELETE CASCADE,
    item_id  UUID    NOT NULL REFERENCES item(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),

    -- One line per item per set: "how many of X does this set contain" has a single answer.
    -- Nothing stops an item joining a second set — that is allowed, and it is the source of the
    -- contention this phase exists to handle.
    CONSTRAINT uq_item_set_line UNIQUE (set_id, item_id)
);

-- The contention query walks this the other way: given an item, which sets contain it.
CREATE INDEX idx_item_set_line_item ON item_set_line (item_id);

SELECT grant_app_privileges();
