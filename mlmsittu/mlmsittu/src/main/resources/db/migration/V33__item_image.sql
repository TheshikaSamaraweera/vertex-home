-- A picture for each item, so a customer browsing the item packs can see what is in them.
--
-- Stored in the document vault like set pictures (V32): the same magic-byte check, the same
-- metadata-stripping re-encode, the same size limit. ON DELETE SET NULL so losing a picture never
-- deletes an item.
ALTER TABLE item
    ADD COLUMN image_id UUID REFERENCES stored_document(id) ON DELETE SET NULL;

-- A sixth kind of document. Readable without a token, like 'announcement' and 'item_set', which
-- is safe only because every token-free read checks the kind: a NIC scan can never carry it.
ALTER TABLE stored_document DROP CONSTRAINT IF EXISTS chk_document_kind;
ALTER TABLE stored_document
    ADD CONSTRAINT chk_document_kind
    CHECK (kind IN ('nic', 'bank_slip', 'announcement', 'item_set', 'item', 'other'));

SELECT grant_app_privileges();
