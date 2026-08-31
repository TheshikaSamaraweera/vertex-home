-- Phase 5 · Sales and payments (architecture §4.3, §5).
--
--   Customer -> SalesOrder -> Reservation -> Payment -> Fulfilment -> stock_movement(-)
--
-- The reservation half already exists from Phase 3. What this migration adds is the paperwork
-- around it: who is buying, what they owe, the slip that says they paid, and the invoice issued
-- once the goods leave.

-- =============================================================================================
-- Customers (P5-01)
-- =============================================================================================

CREATE TABLE customer (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code           VARCHAR(64)  NOT NULL UNIQUE,
    name           VARCHAR(255) NOT NULL,
    email          VARCHAR(320),
    phone          VARCHAR(32),
    address        VARCHAR(500),
    city           VARCHAR(120),

    -- The distributor who introduced them. Attribution and reporting only — §4.4. A customer is
    -- nullable here on purpose: walk-in sales belong to nobody's downline.
    distributor_id UUID REFERENCES distributor(id),

    note           VARCHAR(1000),
    is_active      BOOLEAN      NOT NULL DEFAULT true,
    created_by     UUID NOT NULL REFERENCES app_user(id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Case-insensitive name search, which is how anyone actually looks a customer up.
CREATE INDEX idx_customer_name ON customer (lower(name));
CREATE INDEX idx_customer_distributor ON customer (distributor_id);

-- =============================================================================================
-- Sales orders (P5-02, P5-03)
-- =============================================================================================

-- Order numbers may gap. A rolled-back order is one that never existed and nobody audits the
-- sequence. Invoices are a different matter entirely — see the counter further down.
CREATE SEQUENCE sales_order_number_seq START 1;

CREATE TABLE sales_order (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number   VARCHAR(32) NOT NULL UNIQUE,
    customer_id    UUID NOT NULL REFERENCES customer(id),
    location_id    UUID NOT NULL REFERENCES location(id),

    status         VARCHAR(24) NOT NULL DEFAULT 'awaiting_payment',

    -- The stock this order is holding. Set at creation, cleared in the sense that the reservation
    -- itself moves to 'consumed' or 'released'; the link stays so the history is traceable.
    reservation_id UUID REFERENCES reservation(id),

    subtotal       NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (subtotal >= 0),
    discount       NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    total          NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (total >= 0),

    note           VARCHAR(500),

    -- P5-03. See the unique index below for why both columns are needed.
    idempotency_key     VARCHAR(128),
    request_fingerprint VARCHAR(64),

    created_by     UUID NOT NULL REFERENCES app_user(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at        TIMESTAMPTZ,
    fulfilled_at   TIMESTAMPTZ,
    cancelled_at   TIMESTAMPTZ,

    CONSTRAINT chk_sales_order_status CHECK (status IN (
        'awaiting_payment',   -- created, stock reserved, no slip yet
        'payment_review',     -- a slip is in, finance has not decided
        'paid',               -- payment verified, goods not yet released
        'fulfilled',          -- stock decremented, invoice issued
        'payment_rejected',   -- slip refused, reservation released, buyer may try again
        'cancelled'
    )),

    -- A key without a fingerprint cannot be checked for misuse, and a fingerprint without a key
    -- is dead weight. Either both or neither.
    CONSTRAINT chk_idempotency_pair CHECK (
        (idempotency_key IS NULL) = (request_fingerprint IS NULL))
);

-- P5-03 · idempotency, scoped to the person who sent the key.
--
-- Scoped rather than global because a global namespace lets one operator discover another's keys
-- by collision, and a collision would hand back somebody else's order. Keys are client-chosen
-- strings; treating them as a shared namespace makes them a weak capability.
CREATE UNIQUE INDEX idx_sales_order_idempotency
    ON sales_order (created_by, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_sales_order_customer ON sales_order (customer_id, created_at DESC);
CREATE INDEX idx_sales_order_status ON sales_order (status, created_at DESC);

CREATE TABLE sales_order_line (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_order_id UUID NOT NULL REFERENCES sales_order(id) ON DELETE CASCADE,
    line_no        INTEGER NOT NULL,

    -- Exactly one of the two. A set is kept as a set here even though the reservation behind it
    -- was expanded into components: the customer bought "Starter Pack", and the invoice has to
    -- say so rather than listing six things they never asked for.
    item_id        UUID REFERENCES item(id),
    set_id         UUID REFERENCES item_set(id),

    -- Name and price as they were on the day. The catalogue moves; a historical order must not.
    description    VARCHAR(255) NOT NULL,
    quantity       INTEGER NOT NULL CHECK (quantity > 0),
    unit_price     NUMERIC(14,2) NOT NULL CHECK (unit_price >= 0),
    line_total     NUMERIC(14,2) NOT NULL CHECK (line_total >= 0),

    CONSTRAINT chk_sales_line_names_one_thing CHECK ((item_id IS NULL) <> (set_id IS NULL)),
    CONSTRAINT uq_sales_line_no UNIQUE (sales_order_id, line_no)
);

CREATE INDEX idx_sales_line_item ON sales_order_line (item_id);
CREATE INDEX idx_sales_line_set ON sales_order_line (set_id);

-- =============================================================================================
-- Payments (P5-04 to P5-07, architecture §5)
-- =============================================================================================

CREATE TABLE payment (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_order_id   UUID NOT NULL REFERENCES sales_order(id),
    amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    bank_ref         VARCHAR(128),
    paid_on          DATE,

    -- §5 writes this as `slip_object_key TEXT`. A foreign key into stored_document is used
    -- instead, so a bank slip goes through the same vault as a NIC scan: sanitised on the way in,
    -- and every read of it recorded in document_access_log. A bare object key would have been a
    -- second, unlogged way to hold private images.
    slip_document_id UUID NOT NULL REFERENCES stored_document(id),

    status           VARCHAR(24) NOT NULL DEFAULT 'pending',
    recorded_by      UUID NOT NULL REFERENCES app_user(id),
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_by      UUID REFERENCES app_user(id),
    verified_at      TIMESTAMPTZ,
    rejection_reason VARCHAR(500),

    CONSTRAINT chk_payment_status CHECK (status IN ('pending', 'verified', 'rejected')),

    -- P5-07 · separation of duties, in the database and not only in the service.
    -- Whoever entered the payment cannot be the one who blessed it.
    CONSTRAINT chk_payment_four_eyes CHECK (verified_by IS NULL OR verified_by <> recorded_by),

    -- A decision has to say who made it.
    CONSTRAINT chk_payment_decision_attributed CHECK (
        (status = 'pending') = (verified_by IS NULL))
);

-- P5-05 · the dominant fraud in slip-based systems is one genuine transfer submitted against
-- several orders. Verifiers type the reference off the image and the database refuses the reuse.
-- Partial, because a cash or unreferenced payment legitimately has none.
CREATE UNIQUE INDEX idx_payment_bank_ref ON payment (bank_ref) WHERE bank_ref IS NOT NULL;

CREATE INDEX idx_payment_order ON payment (sales_order_id, recorded_at DESC);
CREATE INDEX idx_payment_status ON payment (status, recorded_at);

-- =============================================================================================
-- Invoices (P5-10)
-- =============================================================================================

-- WHY NOT A SEQUENCE
--
-- nextval() is deliberately non-transactional: it does not roll back, so a failed transaction
-- burns a number and leaves a hole. That is fine for a purchase order and unacceptable for an
-- invoice, where a missing number is the shape of a deleted sale and an auditor will ask about it.
--
-- A counter row locked with SELECT ... FOR UPDATE is transactional. Two invoices being issued at
-- once serialise on the lock, and a crash between allocating a number and writing the invoice
-- rolls both back together — so the number is reused by the next attempt rather than lost.
-- The cost is that invoice issuing is serial, which for the volumes here is not a cost at all.
CREATE TABLE document_counter (
    name       VARCHAR(64) PRIMARY KEY,
    next_value BIGINT NOT NULL CHECK (next_value >= 1)
);

INSERT INTO document_counter (name, next_value) VALUES ('invoice', 1);

CREATE TABLE invoice (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_number VARCHAR(32) NOT NULL UNIQUE,

    -- The raw counter value behind the formatted number. Kept so "are there gaps" is a query
    -- against integers rather than string parsing.
    sequence_no    BIGINT NOT NULL UNIQUE,

    -- One invoice per order. This is what stops a retried fulfilment from issuing a second one:
    -- the database refuses before any number is committed.
    sales_order_id UUID NOT NULL UNIQUE REFERENCES sales_order(id),

    customer_id    UUID NOT NULL REFERENCES customer(id),
    total          NUMERIC(14,2) NOT NULL CHECK (total >= 0),
    issued_by      UUID NOT NULL REFERENCES app_user(id),
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invoice_customer ON invoice (customer_id, issued_at DESC);

SELECT grant_app_privileges();
