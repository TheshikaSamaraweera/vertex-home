-- ==============================================================================================
-- An account is identified by an email address, a phone number, or both.
--
-- Until now email was mandatory and unique, and mobile was an optional note. Many customers here
-- have no email at all, so the office invented one — which is what the shared-inbox aliasing in
-- AccountSignupService existed to make bearable. Making the number a real identifier removes the
-- need for that entirely.
--
-- After this migration:
--
--   email   nullable, unique among the rows that have one
--   mobile  nullable, unique among the rows that have one, stored as +94771234567
--   and at least one of the two must be present
--
-- ==============================================================================================


-- ----------------------------------------------------------------------------------------------
-- 1 · Email becomes optional.
--
-- The unique index has to be rebuilt as a partial one. A plain UNIQUE index would allow many NULLs
-- — PostgreSQL does not consider two NULLs equal — so it would in fact behave correctly here, but
-- the partial form says the rule out loud and stops an index scan walking rows it can never match.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE app_user ALTER COLUMN email DROP NOT NULL;

-- An empty string is not an absent value, and the two must not both be allowed to mean "no email".
-- Anything blank becomes NULL before the constraints below are applied.
UPDATE app_user SET email = NULL WHERE btrim(email) = '';

DROP INDEX IF EXISTS idx_app_user_email;
CREATE UNIQUE INDEX idx_app_user_email
    ON app_user (lower(email))
 WHERE email IS NOT NULL;


-- ----------------------------------------------------------------------------------------------
-- 2 · Mobile becomes an identifier.
--
-- Existing numbers were free text and are in whatever shape somebody typed. They have to be put
-- in one form before a uniqueness rule can mean anything: 077 123 4567 and +94771234567 are the
-- same phone, and an index over the raw text would happily store both.
--
-- This mirrors PhoneNumber.normalise in Java, and the two must stay in step. Doing it here as well
-- is not duplication for its own sake — the rows already in the table were never passed through
-- the Java, and there is no other moment to correct them.
-- ----------------------------------------------------------------------------------------------
UPDATE app_user SET mobile = NULL WHERE btrim(coalesce(mobile, '')) = '';

WITH stripped AS (
    SELECT id,
           regexp_replace(mobile, '[\s.()\-/]', '', 'g') AS digits
      FROM app_user
     WHERE mobile IS NOT NULL
)
UPDATE app_user u
   SET mobile = CASE
                    -- Already carries a country code.
                    WHEN s.digits LIKE '+%'       THEN s.digits
                    -- Local trunk prefix: the 0 is a dialling convention, not part of the number.
                    WHEN s.digits LIKE '0%'       THEN '+94' || substring(s.digits FROM 2)
                    -- Country code without the plus.
                    WHEN s.digits LIKE '94%'
                     AND length(s.digits) > 9     THEN '+' || s.digits
                    -- Bare subscriber number.
                    WHEN s.digits ~ '^[0-9]{9}$'  THEN '+94' || s.digits
                    -- Anything else is not a number this can read. Left alone deliberately rather
                    -- than guessed at or discarded: see the guard below.
                    ELSE s.digits
                END
  FROM stripped s
 WHERE s.id = u.id;


-- ----------------------------------------------------------------------------------------------
-- Refuse rather than corrupt.
--
-- Two ways this migration could silently do damage, and both are worth stopping for.
--
-- A number that did not normalise would become an identifier nobody can log in with, because the
-- Java would produce a different string from the same input. Duplicates would mean two accounts
-- claiming one phone, and the unique index below would fail anyway — but with a constraint
-- violation naming an index, rather than a message saying which rows and what to do.
-- ----------------------------------------------------------------------------------------------
DO $guard$
DECLARE
    unreadable INTEGER;
    duplicated INTEGER;
BEGIN
    SELECT count(*) INTO unreadable
      FROM app_user
     WHERE mobile IS NOT NULL
       AND mobile !~ '^\+[0-9]{7,15}$';

    IF unreadable > 0 THEN
        RAISE EXCEPTION
            '% account(s) have a mobile number that is not a usable phone number. '
            'They are now login identifiers, so they cannot be left as free text. '
            'Find them with:  SELECT id, email, mobile FROM app_user '
            'WHERE mobile IS NOT NULL AND mobile !~ ''^\+[0-9]{7,15}$'';  '
            'Correct or clear each one, then re-run.', unreadable;
    END IF;

    SELECT count(*) INTO duplicated
      FROM (SELECT mobile FROM app_user
             WHERE mobile IS NOT NULL
             GROUP BY mobile HAVING count(*) > 1) d;

    IF duplicated > 0 THEN
        RAISE EXCEPTION
            '% phone number(s) are shared by more than one account. A number identifies exactly '
            'one account from here on. Find them with:  SELECT mobile, count(*) FROM app_user '
            'WHERE mobile IS NOT NULL GROUP BY mobile HAVING count(*) > 1;  '
            'Clear the number on all but one account, then re-run.', duplicated;
    END IF;
END
$guard$;

CREATE UNIQUE INDEX idx_app_user_mobile
    ON app_user (mobile)
 WHERE mobile IS NOT NULL;


-- ----------------------------------------------------------------------------------------------
-- 3 · One of the two is mandatory.
--
-- Without this an account could exist with neither, which is an account nobody can ever sign in
-- to and nobody can contact — recoverable only by an administrator editing the database.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_has_identifier
    CHECK (email IS NOT NULL OR mobile IS NOT NULL);


-- ----------------------------------------------------------------------------------------------
-- 4 · Email verification is no longer part of signing up.
--
-- An account is usable the moment it is created. The column and the token table stay: email_
-- verified still records whether an address was ever confirmed, which is worth knowing before
-- sending anything important to it, and the table holds history.
--
-- Existing unverified accounts are activated. Leaving them would be the worst of both worlds —
-- people who registered under the old rules, whose confirmation link is now the only way through
-- a door that no longer exists.
-- ----------------------------------------------------------------------------------------------
UPDATE app_user
   SET status = 'active'
 WHERE status = 'unverified';

SELECT grant_app_privileges();
