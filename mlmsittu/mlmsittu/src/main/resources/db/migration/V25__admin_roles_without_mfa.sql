-- ==============================================================================================
-- ADMIN and SUPER_ADMIN no longer require TOTP.
--
-- The client asked for this during Phase 8. It was applied by hand to the development database
-- and never written down, so it was not a decision the system held — it was a local edit. Every
-- fresh deployment brought the requirement straight back, and the first person to find out was
-- whoever tried to sign in to a new server and was met with an enrolment QR nobody expected.
--
-- V2 seeded every role with requires_mfa = true and V14 added ADMIN the same way. Both were right
-- at the time. This supersedes them for these two roles only.
--
-- The staff roles keep it: KYC_REVIEWER, INVENTORY_CLERK, PROCUREMENT_OFFICER, FINANCE_OFFICER
-- and SUPPORT_AGENT are still handed an enrolment QR on first login and cannot sign in until they
-- scan it. Those accounts belong to people who are given a login to do one job, and a second
-- factor is cheap insurance on an account somebody else administers.
--
-- To reverse it, one statement — no migration needed, because it is configuration rather than
-- schema:
--
--     UPDATE app_role SET requires_mfa = true WHERE code IN ('ADMIN', 'SUPER_ADMIN');
--
-- AuthService reads this column on every login, so it takes effect at the next sign-in with no
-- restart. An administrator who has already enrolled keeps their secret and is simply asked for
-- the code again.
-- ==============================================================================================

UPDATE app_role
   SET requires_mfa = false
 WHERE code IN ('ADMIN', 'SUPER_ADMIN');
