-- Client change, 26 Aug 2026: the suppliers screen becomes a record of the supplier, not just a
-- name to hang purchase orders on.
--
-- The existing columns conflated two different people. `contact_name` was the person you speak to,
-- but `email` and `phone` were the company's — so there was nowhere to put the contact's own
-- number, which is the one anybody actually dials. These two columns separate them.

ALTER TABLE supplier
    ADD COLUMN contact_email VARCHAR(320),
    ADD COLUMN contact_phone VARCHAR(32);

COMMENT ON COLUMN supplier.email IS 'The company address — accounts@, orders@, that sort of thing.';
COMMENT ON COLUMN supplier.phone IS 'The company switchboard.';
COMMENT ON COLUMN supplier.contact_email IS 'The named contact''s own address.';
COMMENT ON COLUMN supplier.contact_phone IS 'The named contact''s own number.';
COMMENT ON COLUMN supplier.address IS 'Where they are — shown as "Location" on the screen.';

SELECT grant_app_privileges();
