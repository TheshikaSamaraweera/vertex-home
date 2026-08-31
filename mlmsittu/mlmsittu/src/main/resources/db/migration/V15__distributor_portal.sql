-- The distributor portal, requested 16 Aug 2026.
--
-- Until now everybody signed in at one door and staff roles decided what they saw. Distributors are
-- not staff: they are the customers of this system, they arrive by self-signup, and they should see
-- their own affairs and nothing else. This migration adds the role that makes the two
-- distinguishable.

-- =============================================================================================
-- The distributor role
-- =============================================================================================
--
-- WHY NO MFA
--
-- Every other role carries requires_mfa = true, and V2's own comment anticipated this one being
-- different: "distributor accounts added in Phase 4 will not". An authenticator app is a reasonable
-- thing to ask of eight staff who are paid to be here. It is not a reasonable thing to ask of a
-- distributor signing up on a phone, and demanding it would simply stop people registering — the
-- risk of which is worse than the risk it mitigates, because a distributor account can read its own
-- details and place nothing.
--
-- If the client later wants MFA for distributors, this single column is where it changes, and the
-- login flow already handles both paths.

INSERT INTO app_role (code, description, requires_mfa) VALUES
    ('DISTRIBUTOR', 'A registered distributor. Sees their own account, stages and referrals.', false)
ON CONFLICT (code) DO NOTHING;

-- =============================================================================================
-- Backfill: everybody who already has a distributor record
-- =============================================================================================
--
-- The seeded referral tree and anyone approved before today has a distributor row but no role, so
-- they would arrive at the portal and be told they are not a distributor. Granting it retroactively
-- keeps existing test data usable.

INSERT INTO user_role (user_id, role_id)
SELECT DISTINCT d.user_id, r.id
FROM distributor d
CROSS JOIN app_role r
WHERE r.code = 'DISTRIBUTOR'
ON CONFLICT DO NOTHING;

-- And anyone with a registration in flight but no distributor record yet — they signed up, so they
-- are here to be a distributor whatever the outcome of their review.
INSERT INTO user_role (user_id, role_id)
SELECT DISTINCT reg.user_id, r.id
FROM registration reg
CROSS JOIN app_role r
WHERE r.code = 'DISTRIBUTOR'
ON CONFLICT DO NOTHING;

SELECT grant_app_privileges();
