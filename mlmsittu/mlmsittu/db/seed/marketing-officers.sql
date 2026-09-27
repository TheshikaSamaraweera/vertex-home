-- ==============================================================================================
-- Marketing officers — starting data.
--
--   docker compose exec -T db psql -U postgres -d mlmsitty < mlmsittu/db/seed/marketing-officers.sql
--
-- Creates two officer accounts and assigns every customer who has none to one of them, so the
-- screens have something in them the first time somebody opens them.
--
-- NOT a migration, and deliberately not in db/migration. Migrations are schema and run on every
-- deployment; this is starting data somebody chose, and a second deployment may want different
-- officers or none at all.
--
-- Safe to run twice. The accounts are keyed on their email addresses and conflicts are ignored,
-- and the assignment only touches customers who do not already have an officer — so a re-run
-- never moves somebody who has already been placed.
--
-- THE PASSWORDS BELOW ARE TEMPORARY AND PUBLISHED IN THIS REPOSITORY.
-- Each account is flagged to change its password at first sign-in, so the one written here stops
-- working the moment the officer uses it. Hand them over and have them signed in the same day.
-- ==============================================================================================

BEGIN;

-- ----------------------------------------------------------------------------------------------
-- 1 · The accounts.
--
-- The hash below is Argon2id and came from this application's own encoder, by registering an
-- account and reading the column back. Writing one by hand with different parameters produces an
-- account whose login fails in a way that looks exactly like a wrong password — the same trap
-- DEPLOYMENT.md section 6 describes for the first administrator.
--
-- Both are 'Officer123', which satisfies the 8-12 policy and is replaced at first sign-in.
-- ----------------------------------------------------------------------------------------------
INSERT INTO app_user (email, mobile, full_name, password_hash, status, email_verified, must_change_password)
VALUES
    ('officer.one@vertexhome.lk', '+94770000101', 'Marketing Officer One',
     '$argon2id$v=19$m=65536,t=3,p=1$cqd6zEeEe3Mgq6vGPtzCVQ$zrKQ4K9ag8NyoZ+RcwYk81UxbndyAwJxq+m3duBoxLA',
     'active', true, true),
    ('officer.two@vertexhome.lk', '+94770000102', 'Marketing Officer Two',
     '$argon2id$v=19$m=65536,t=3,p=1$cqd6zEeEe3Mgq6vGPtzCVQ$zrKQ4K9ag8NyoZ+RcwYk81UxbndyAwJxq+m3duBoxLA',
     'active', true, true)
ON CONFLICT DO NOTHING;


-- ----------------------------------------------------------------------------------------------
-- 2 · The role and the rate.
--
-- One per cent each, which is the column default — written explicitly so the file states the rate
-- rather than leaving it to whatever the default happens to be when it runs.
-- ----------------------------------------------------------------------------------------------
INSERT INTO marketing_officer (user_id, commission_rate)
SELECT id, 0.0100 FROM app_user WHERE email IN ('officer.one@vertexhome.lk', 'officer.two@vertexhome.lk')
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
  FROM app_user u, app_role r
 WHERE u.email IN ('officer.one@vertexhome.lk', 'officer.two@vertexhome.lk')
   AND r.code = 'MARKETING_OFFICER'
ON CONFLICT DO NOTHING;


-- ----------------------------------------------------------------------------------------------
-- 3 · Assign the customers who have nobody.
--
-- Alternating between the two officers by row number, so the screens show a realistic split
-- rather than one officer holding everything and the other holding nothing.
--
-- Only customers with no officer: a re-run must never move somebody who has already been placed
-- by hand, which is the whole reason this is not a blanket UPDATE.
-- ----------------------------------------------------------------------------------------------
WITH officers AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY email) - 1 AS slot
      FROM app_user
     WHERE email IN ('officer.one@vertexhome.lk', 'officer.two@vertexhome.lk')
),
unassigned AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY created_at, id) - 1 AS seq
      FROM distributor
     WHERE marketing_officer_id IS NULL
       AND deleted_at IS NULL
       AND business_id IS NOT NULL
)
UPDATE distributor d
   SET marketing_officer_id = o.id, updated_at = now()
  FROM unassigned u
  JOIN officers o ON o.slot = u.seq % (SELECT count(*) FROM officers)
 WHERE d.id = u.id;

COMMIT;


-- ----------------------------------------------------------------------------------------------
-- What went in.
-- ----------------------------------------------------------------------------------------------
SELECT u.full_name                       AS officer,
       u.email,
       (mo.commission_rate * 100) || '%' AS rate,
       count(d.id)                       AS customers
  FROM marketing_officer mo
  JOIN app_user u        ON u.id = mo.user_id
  LEFT JOIN distributor d ON d.marketing_officer_id = u.id AND d.deleted_at IS NULL
 GROUP BY u.full_name, u.email, mo.commission_rate
 ORDER BY u.full_name;
