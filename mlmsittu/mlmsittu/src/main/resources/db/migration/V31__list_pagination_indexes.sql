-- Indexes for the keyset pages the list screens now read one at a time.
--
-- Each matches its query's ORDER BY exactly, id included. The id is the tie-breaker that makes
-- the order total, and an index without it would still have to sort every tie before it could
-- seek past the cursor — which is the cost this whole change exists to remove.
--
-- Invoices need nothing new: sequence_no is already UNIQUE, so already indexed.
CREATE INDEX idx_sales_order_created_id ON sales_order (created_at DESC, id DESC);
CREATE INDEX idx_purchase_order_created_id ON purchase_order (created_at DESC, id DESC);
CREATE INDEX idx_goods_receipt_received_id ON goods_receipt (received_at DESC, id DESC);
CREATE INDEX idx_customer_name_id ON customer (name, id);
