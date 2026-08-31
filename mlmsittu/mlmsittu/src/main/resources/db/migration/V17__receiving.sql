-- Receiving: arrival is attested first, stock moves later.
--
-- Until now "receive" meant two things at once — the goods turned up, and they went on a shelf.
-- The client's warehouse does those separately and often hours apart, so the schema now does too:
--
--   sent ──confirm arrival──▶ arrived ──add to stores──▶ partially_received ──▶ received
--
-- Nothing in the ledger changes. Stock still only moves when a goods_receipt is written; what
-- moved is *when* that happens, which is now the "add to stores" step rather than the moment a
-- lorry pulls in.

-- ---------------------------------------------------------------- purchase order: the attestation

ALTER TABLE purchase_order
    ADD COLUMN sent_to_email        VARCHAR(320),
    ADD COLUMN arrived_at           TIMESTAMPTZ,
    ADD COLUMN arrived_by           UUID REFERENCES app_user(id),
    ADD COLUMN arrival_attested_name VARCHAR(255);

COMMENT ON COLUMN purchase_order.sent_to_email IS
    'Where the order document was actually emailed. Kept because the supplier''s address can change afterwards, and "we sent it" must mean the address of the day.';
COMMENT ON COLUMN purchase_order.arrival_attested_name IS
    'What the person typed to confirm the delivery arrived. Their own name, checked against the account — a signature, not an identifier.';

ALTER TABLE purchase_order DROP CONSTRAINT chk_po_status;
ALTER TABLE purchase_order ADD CONSTRAINT chk_po_status CHECK (status IN
    ('draft', 'sent', 'arrived', 'partially_received', 'received', 'cancelled'));

-- All three or none. A row claiming an arrival with nobody attached to it is worse than no row.
ALTER TABLE purchase_order ADD CONSTRAINT chk_po_arrival CHECK (
    (arrived_at IS NULL AND arrived_by IS NULL AND arrival_attested_name IS NULL)
    OR (arrived_at IS NOT NULL AND arrived_by IS NOT NULL AND arrival_attested_name IS NOT NULL));

-- ---------------------------------------------------------------- goods receipt: manual entries

-- A manual entry has no order behind it: goods turn up from a supplier who was never sent a PO,
-- or an old delivery is being written up after the fact.
ALTER TABLE goods_receipt ALTER COLUMN purchase_order_id DROP NOT NULL;

ALTER TABLE goods_receipt
    ADD COLUMN supplier_id UUID REFERENCES supplier(id),
    ADD COLUMN source      VARCHAR(16) NOT NULL DEFAULT 'purchase_order';

-- Existing receipts all came from an order; take the supplier from it rather than leaving a gap.
UPDATE goods_receipt gr
   SET supplier_id = po.supplier_id
  FROM purchase_order po
 WHERE po.id = gr.purchase_order_id
   AND gr.supplier_id IS NULL;

ALTER TABLE goods_receipt ALTER COLUMN supplier_id SET NOT NULL;

ALTER TABLE goods_receipt ADD CONSTRAINT chk_receipt_source
    CHECK (source IN ('purchase_order', 'manual'));

-- The pairing is what stops a manual entry quietly attaching itself to an order, which would
-- inflate that order's received quantities without any line ever being closed.
ALTER TABLE goods_receipt ADD CONSTRAINT chk_receipt_origin CHECK (
    (source = 'purchase_order' AND purchase_order_id IS NOT NULL)
    OR (source = 'manual' AND purchase_order_id IS NULL));

CREATE INDEX idx_receipt_supplier ON goods_receipt (supplier_id);
CREATE INDEX idx_receipt_received_at ON goods_receipt (received_at DESC);

-- ---------------------------------------------------------------- receipt line: its own store

-- The store is now chosen per line, not per order. One delivery routinely splits across two
-- stores, and forcing a single one made people book two receipts for one lorry.
ALTER TABLE goods_receipt_line ALTER COLUMN purchase_order_line_id DROP NOT NULL;

ALTER TABLE goods_receipt_line
    ADD COLUMN item_id     UUID REFERENCES item(id),
    ADD COLUMN location_id UUID REFERENCES location(id);

UPDATE goods_receipt_line grl
   SET item_id = pol.item_id
  FROM purchase_order_line pol
 WHERE pol.id = grl.purchase_order_line_id
   AND grl.item_id IS NULL;

UPDATE goods_receipt_line grl
   SET location_id = gr.location_id
  FROM goods_receipt gr
 WHERE gr.id = grl.goods_receipt_id
   AND grl.location_id IS NULL;

ALTER TABLE goods_receipt_line ALTER COLUMN item_id SET NOT NULL;
ALTER TABLE goods_receipt_line ALTER COLUMN location_id SET NOT NULL;

CREATE INDEX idx_receipt_line_item ON goods_receipt_line (item_id);

SELECT grant_app_privileges();
