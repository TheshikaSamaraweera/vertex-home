-- Client changes requested after the first demo, 16 Aug 2026.
--
--   1. An "admin" role that can do everything except manage users.
--   2. Retail and wholesale prices on an item, settable only by a super admin.
--   3. A price per supplier per item, so purchase orders know what each supplier charges.

-- =============================================================================================
-- 1 · The admin role
-- =============================================================================================
--
-- Sits between super_admin and the five specialist roles. The distinction that gives it a reason
-- to exist is user management: an admin runs the business day to day but cannot grant themselves
-- or anybody else a role. That is the one privilege that can be used to acquire every other
-- privilege, so it stays with super_admin alone.
--
-- The implication chain (super_admin -> admin -> the five) lives in Spring Security's
-- RoleHierarchy rather than in this table, because it is an authorisation rule and not data.

INSERT INTO app_role (code, description, requires_mfa) VALUES
    ('ADMIN', 'Everything except user and role management.', true)
ON CONFLICT (code) DO NOTHING;

-- =============================================================================================
-- 2 · Retail and wholesale prices
-- =============================================================================================
--
-- Recorded for reference. `selling_price` remains the figure a sales order charges, so nothing
-- about the existing order flow changes; these two are what the business quotes, visible to
-- everyone and editable only by a super admin (enforced in the service layer, since a column
-- cannot know who is writing to it).
--
-- Nullable rather than defaulted: "no wholesale price has been set" and "the wholesale price is
-- zero" are different statements, and a default of 0 would quietly turn the first into the second.

ALTER TABLE item
    ADD COLUMN retail_price    NUMERIC(14,2) CHECK (retail_price >= 0),
    ADD COLUMN wholesale_price NUMERIC(14,2) CHECK (wholesale_price >= 0);

COMMENT ON COLUMN item.retail_price IS
    'Reference price for retail customers. Orders charge selling_price; this does not affect them.';
COMMENT ON COLUMN item.wholesale_price IS
    'Reference price for wholesale customers. Orders charge selling_price; this does not affect them.';

-- =============================================================================================
-- 3 · Supplier prices
-- =============================================================================================
--
-- What each supplier charges for each item. A purchase order line defaults to the price of the
-- supplier the order is going to, falling back to the item's own unit_cost when that supplier has
-- never quoted for it.
--
-- WHY A TABLE RATHER THAN A COLUMN ON item
--
-- The same goods are bought from several suppliers at different prices — that is the whole point
-- of having more than one. A single column would force whoever maintains it to overwrite the
-- others' prices, and the question "who is cheapest for this" would have no answer.
--
-- WHY THIS LIVES WITH PROCUREMENT AND NOT WITH THE CATALOGUE
--
-- It references supplier, which belongs to commerce. The catalogue module has no business knowing
-- suppliers exist — an item is a product, and what it costs to buy is a procurement fact about it.

CREATE TABLE item_supplier_price (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id     UUID NOT NULL REFERENCES item(id) ON DELETE CASCADE,
    supplier_id UUID NOT NULL REFERENCES supplier(id),

    price       NUMERIC(14,2) NOT NULL CHECK (price >= 0),

    -- Free text: "per case of 24", "landed, excludes duty". The number alone loses the terms it
    -- was agreed under, and that is what the next negotiation argues about.
    note        VARCHAR(255),

    created_by  UUID NOT NULL REFERENCES app_user(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One price per supplier per item. Two rows would make "what does SUP-001 charge" ambiguous.
    CONSTRAINT uq_item_supplier UNIQUE (item_id, supplier_id)
);

CREATE INDEX idx_isp_supplier ON item_supplier_price (supplier_id);

SELECT grant_app_privileges();
