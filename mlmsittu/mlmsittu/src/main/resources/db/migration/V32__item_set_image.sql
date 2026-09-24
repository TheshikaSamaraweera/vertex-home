-- A picture for an item set, so a pack can be recognised at a glance on the sets screen.
--
-- Held in the document vault like every other upload, so it gets the same magic-byte check, the
-- same re-encode that strips metadata, and the same size limit. ON DELETE SET NULL rather than
-- CASCADE: losing the picture must not delete the set.
ALTER TABLE item_set
    ADD COLUMN image_id UUID REFERENCES stored_document(id) ON DELETE SET NULL;

-- A fifth kind of document. Like 'announcement' it is a picture for staff screens rather than
-- anybody's paperwork, and the serving endpoint reads it without a single-use token — which is
-- only safe because the kind is checked there, so a NIC scan can never be served this way.
ALTER TABLE stored_document DROP CONSTRAINT IF EXISTS chk_document_kind;
ALTER TABLE stored_document
    ADD CONSTRAINT chk_document_kind
    CHECK (kind IN ('nic', 'bank_slip', 'announcement', 'item_set', 'other'));

SELECT grant_app_privileges();
