-- ==============================================================================================
-- Membership expires.
--
-- A customer's place is good for a period counted from the day their registration was approved.
-- Default two months, set by an administrator, and extendable for one person at a time when
-- somebody renews.
--
-- WHAT EXPIRY DOES, AND WHAT IT DELIBERATELY DOES NOT
-- ---------------------------------------------------
-- It closes the portal. That is all.
--
-- It does not touch the tree. An expired customer keeps their Business ID, keeps their place, and
-- still counts as their referrer's referral — so their referrer's stages and any reward already
-- earned are unaffected by somebody else forgetting to renew. That is the whole reason to stop at
-- the portal: a lapsed membership is between the business and that person, and letting it reach
-- into a third party's entitlement would make one missed payment somebody else's problem.
--
-- Extending therefore restores everything exactly, because nothing was ever taken away.
-- ==============================================================================================

ALTER TABLE distributor ADD COLUMN expires_at TIMESTAMPTZ;

-- The reminder sweep asks "who expires soon and has not been told", which is a range scan over
-- this column across the whole table. Pending distributors have no expiry and never will until
-- they are approved, so they are left out of the index entirely.
CREATE INDEX idx_distributor_expires ON distributor (expires_at)
 WHERE expires_at IS NOT NULL AND deleted_at IS NULL;

-- When each reminder was last sent, so a nightly sweep does not send the same warning every night
-- for a fortnight. Null until the first one goes out.
ALTER TABLE distributor ADD COLUMN expiry_reminded_at TIMESTAMPTZ;


-- ----------------------------------------------------------------------------------------------
-- The period, in days.
--
-- Days rather than months, because "two months" has no fixed length and the arithmetic has to be
-- the same everywhere — 60 days from the 31st of January is a date, where two months is an
-- argument. The default is stated in the description so an administrator changing it can see what
-- it was.
-- ----------------------------------------------------------------------------------------------
INSERT INTO system_config (key, value, description) VALUES
    ('membership.period_days', '60',
     'How long a membership lasts, in days, counted from approval. Default 60 (about two months). '
     'Changing this affects registrations approved from then on; it does not move anybody''s '
     'existing expiry date.')
ON CONFLICT (key) DO NOTHING;


-- ----------------------------------------------------------------------------------------------
-- Everybody already approved gets a date.
--
-- Counted from their own approval, not from today, so the figures are honest about who is already
-- overdue rather than quietly giving every existing customer a fresh two months.
--
-- There is a real consequence: a customer approved three months ago is expired the moment this
-- migration runs, and cannot open the portal until somebody extends them. That is the correct
-- reading of the rule — they have had their period — and it is visible immediately on the customer
-- list rather than surfacing one at a time as people try to sign in.
-- ----------------------------------------------------------------------------------------------
UPDATE distributor
   SET expires_at = COALESCE(approved_at, created_at)
                  + (SELECT value::INTEGER FROM system_config WHERE key = 'membership.period_days')
                  * INTERVAL '1 day'
 WHERE business_id IS NOT NULL
   AND expires_at IS NULL;

SELECT grant_app_privileges();
