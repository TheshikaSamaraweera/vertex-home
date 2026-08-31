-- P3-04 · Reservation (architecture §4.5).
--
-- Reservation is the authoritative claim on stock. Availability figures are advisory — two sets
-- sharing a component can each report "available" when only one can actually ship, so the only
-- question that has a real answer is "did the reservation succeed".

CREATE TABLE reservation (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- What this reservation is for. Phase 5 sets these to the sales order; until then a
    -- reservation can stand alone so the mechanism can be exercised without an order.
    reference_type VARCHAR(32),
    reference_id   UUID,

    location_id    UUID NOT NULL REFERENCES location(id),
    status         VARCHAR(24) NOT NULL DEFAULT 'active',

    -- Null means "holds until someone releases it". A TTL exists so an abandoned checkout does
    -- not hold stock out of circulation forever.
    expires_at     TIMESTAMPTZ,

    created_by     UUID NOT NULL REFERENCES app_user(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at    TIMESTAMPTZ,
    release_reason VARCHAR(64),

    CONSTRAINT chk_reservation_status
        CHECK (status IN ('active', 'released', 'consumed', 'expired'))
);

CREATE INDEX idx_reservation_status ON reservation (status, created_at DESC);
CREATE INDEX idx_reservation_reference ON reservation (reference_type, reference_id);

-- The expiry sweep looks only at live rows, so the index only carries those.
CREATE INDEX idx_reservation_expiry ON reservation (expires_at) WHERE status = 'active';

CREATE TABLE reservation_line (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID    NOT NULL REFERENCES reservation(id) ON DELETE CASCADE,
    item_id        UUID    NOT NULL REFERENCES item(id),
    quantity       INTEGER NOT NULL CHECK (quantity > 0),

    -- Lines hold EXPANDED components, aggregated per item — not the sets the caller asked for.
    -- Release has to give back exactly what reserve took, and after set expansion the only thing
    -- that was actually taken is a quantity of a component item. Which set it came from is a
    -- question for the sales order, not for stock.
    CONSTRAINT uq_reservation_line_item UNIQUE (reservation_id, item_id)
);

CREATE INDEX idx_reservation_line_item ON reservation_line (item_id);

SELECT grant_app_privileges();
