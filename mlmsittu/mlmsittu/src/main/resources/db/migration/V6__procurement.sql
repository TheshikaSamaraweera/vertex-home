-- P2-06, P2-07, P2-08 · Suppliers, purchase orders, goods receipt (architecture §4.3).
--
--   Supplier -> PurchaseOrder -> GoodsReceipt -> stock_movement(+)

CREATE TABLE supplier (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(64)  NOT NULL UNIQUE,
    name         VARCHAR(255) NOT NULL,
    contact_name VARCHAR(255),
    email        VARCHAR(320),
    phone        VARCHAR(32),
    address      VARCHAR(500),
    is_active    BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Human-facing document numbers. A sequence can leave gaps when a transaction rolls back, which
-- is fine for a purchase order. Invoice numbers in Phase 5 (P5-10) must be gap-free and need a
-- different mechanism — do not copy this pattern there.
CREATE SEQUENCE purchase_order_number_seq START 1;
CREATE SEQUENCE goods_receipt_number_seq START 1;

CREATE TABLE purchase_order (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    po_number     VARCHAR(32)  NOT NULL UNIQUE,
    supplier_id   UUID NOT NULL REFERENCES supplier(id),
    location_id   UUID NOT NULL REFERENCES location(id),
    status        VARCHAR(24)  NOT NULL DEFAULT 'draft',
    expected_date DATE,
    note          VARCHAR(500),
    created_by    UUID NOT NULL REFERENCES app_user(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at       TIMESTAMPTZ,
    closed_at     TIMESTAMPTZ,

    CONSTRAINT chk_po_status CHECK (status IN
        ('draft', 'sent', 'partially_received', 'received', 'cancelled'))
);

CREATE INDEX idx_po_supplier ON purchase_order (supplier_id);
CREATE INDEX idx_po_status ON purchase_order (status, created_at DESC);

CREATE TABLE purchase_order_line (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_order_id UUID NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    item_id           UUID NOT NULL REFERENCES item(id),
    line_no           INTEGER NOT NULL,
    quantity_ordered  INTEGER NOT NULL CHECK (quantity_ordered > 0),
    quantity_received INTEGER NOT NULL DEFAULT 0 CHECK (quantity_received >= 0),
    unit_cost         NUMERIC(14,2) NOT NULL CHECK (unit_cost >= 0),

    -- The database refuses over-receipt outright. The service returns a clean 409 first; this is
    -- what catches a code path that forgets to ask.
    CONSTRAINT chk_not_over_received CHECK (quantity_received <= quantity_ordered),

    -- One line per item per order, so "how much of X did we order" has a single answer.
    CONSTRAINT uq_po_line_item UNIQUE (purchase_order_id, item_id),
    CONSTRAINT uq_po_line_no UNIQUE (purchase_order_id, line_no)
);

CREATE INDEX idx_po_line_item ON purchase_order_line (item_id);

CREATE TABLE goods_receipt (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_number    VARCHAR(32) NOT NULL UNIQUE,
    purchase_order_id UUID NOT NULL REFERENCES purchase_order(id),
    location_id       UUID NOT NULL REFERENCES location(id),
    supplier_note     VARCHAR(500),
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID NOT NULL REFERENCES app_user(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_receipt_po ON goods_receipt (purchase_order_id);

CREATE TABLE goods_receipt_line (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goods_receipt_id       UUID NOT NULL REFERENCES goods_receipt(id) ON DELETE CASCADE,
    purchase_order_line_id UUID NOT NULL REFERENCES purchase_order_line(id),
    quantity_received      INTEGER NOT NULL CHECK (quantity_received > 0)
);

CREATE INDEX idx_receipt_line_po_line ON goods_receipt_line (purchase_order_line_id);

SELECT grant_app_privileges();
