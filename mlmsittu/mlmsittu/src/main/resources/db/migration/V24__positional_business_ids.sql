-- ==============================================================================================
-- Positional Business IDs
--
-- The identifier now encodes the person's seat in the referral tree rather than an opaque
-- sequence value:
--
--     1                  the first root customer
--     11 12 13 14 15     that customer's five children
--     121 ... 125        the children of 12
--     1431 ... 1435      the children of 143
--
-- The identifier IS the path. Every prefix of an ID is an ancestor's ID, so "who is above this
-- person" is answerable by reading the number, with no query at all.
--
-- Replaces SLV-XXXXX-C (see shared/businessid/BusinessId.java), which was a checksummed sequence
-- value carrying no structure. That class stays for decoding historical references; nothing issues
-- one any more.
--
-- Roots are limited to nine, and that limit is load-bearing: seats run 1-5, so concatenation is
-- only unambiguous while every root is a single character. A tenth root numbered 11 would have a
-- first child 111, which root 1 already owns by way of seat 1 then seat 1.
-- ==============================================================================================


-- ----------------------------------------------------------------------------------------------
-- Refuse rather than collide.
--
-- The renumbering below hands roots plain ROW_NUMBER values: 1, 2, 3 ... 10, 11. From ten onwards
-- those are not valid roots. A root may only use 0 and 6-9 after its first digit -- see
-- PositionalId -- precisely so it can never be confused with a seat chain, and root 11 would be
-- indistinguishable from root 1's seat-1-then-seat-1 grandchild. Both read 111.
--
-- The running application allocates roots correctly for any number of them. This one statement
-- does not, so it refuses rather than corrupting: the failure is silent and unrecoverable
-- otherwise, because nothing afterwards could say which person an identifier meant.
-- ----------------------------------------------------------------------------------------------
DO $$
DECLARE
    roots INTEGER;
BEGIN
    SELECT count(*) INTO roots
      FROM distributor
     WHERE referred_by IS NULL AND business_id IS NOT NULL;

    IF roots > 9 THEN
        RAISE EXCEPTION
            'This migration renumbers at most 9 root customers, but this database has %. '
            'A root is a distributor with no referrer; normal registration always names one, so '
            'these come from seeding. Re-parent the extras or delete them, then re-run.', roots;
    END IF;
END $$;


-- One character per generation, so the old VARCHAR(12) capped the tree at eleven levels.
--
-- referral_summary selects business_id, and PostgreSQL refuses to alter a column a view depends
-- on — even a widening that cannot affect the view's own type. Dropping and recreating it is the
-- only way; the definition below is V11's, unchanged.
DROP VIEW IF EXISTS referral_summary;

ALTER TABLE distributor      ALTER COLUMN business_id          TYPE VARCHAR(64);
ALTER TABLE registration     ALTER COLUMN referrer_business_id TYPE VARCHAR(64);

-- Recreated identically. Attribution and reporting only: architecture §4.4 (amended) requires
-- entitlement logic to read stored, transactionally guarded state and never this view, because
-- recomputing a count at decision time is the race the row lock exists to prevent.
CREATE VIEW referral_summary AS
SELECT d.id,
       d.business_id,
       d.referred_by,
       d.path,
       (SELECT count(*) FROM distributor c
         WHERE c.referred_by = d.id AND c.status = 'active') AS direct_count
FROM distributor d;


-- ----------------------------------------------------------------------------------------------
-- Renumber everybody who already has an ID.
--
-- Walks down from the roots. Each node's new ID is its parent's new ID with its seat number
-- appended; seats are handed out in path order, which is the order the referrals were accepted.
--
-- Ordering by `path` rather than `created_at` on purpose: the path is the structural truth and is
-- stable, while two rows created in the same millisecond during seeding would order arbitrarily
-- and could produce a different answer on a re-run.
-- ----------------------------------------------------------------------------------------------
WITH RECURSIVE numbered AS (

    SELECT d.id,
           ROW_NUMBER() OVER (ORDER BY d.path)::TEXT AS new_id
      FROM distributor d
     WHERE d.referred_by IS NULL
       AND d.business_id IS NOT NULL

    UNION ALL

    SELECT child.id,
           parent.new_id
               || ROW_NUMBER() OVER (PARTITION BY child.referred_by ORDER BY child.path)::TEXT
      FROM distributor child
      JOIN numbered parent ON parent.id = child.referred_by
     WHERE child.business_id IS NOT NULL
)
UPDATE distributor d
   SET business_id = n.new_id
  FROM numbered n
 WHERE n.id = d.id;


-- Registrations quote their referrer's ID as free text, captured when the form was filled in.
-- Those strings now name nobody, so they are rewritten to match.
UPDATE registration r
   SET referrer_business_id = d.business_id
  FROM distributor d
 WHERE d.id = (SELECT referred_by FROM distributor WHERE user_id = r.user_id)
   AND r.referrer_business_id IS NOT NULL;


-- ----------------------------------------------------------------------------------------------
-- Referral card codes are no longer random.
--
-- A card's child ID is now the parent's ID with the card's number appended: card 3 printed for
-- parent 12 reads 123, which is exactly the ID that child will receive on approval.
--
-- That makes codes repeat by design — reprinting a batch produces the same five identifiers,
-- because card 3 for parent 12 is always 123. The global UNIQUE constraint was right for random
-- codes and is wrong for these.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE referral_card DROP CONSTRAINT IF EXISTS referral_card_code_key;
ALTER TABLE referral_card ALTER COLUMN code TYPE VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_referral_card_code ON referral_card (code);


-- Cards printed before this migration carry random codes that name nobody. Rewrite them to the
-- identifier each card now promises: the parent's new Business ID with the card number appended.
--
-- Runs after the constraint is dropped, and it has to: two batches printed for the same parent
-- both yield that parent's five child IDs, which is correct and would violate the old index.
UPDATE referral_card c
   SET code = d.business_id || c.card_number::TEXT
  FROM referral_card_batch b
  JOIN distributor d ON d.id = b.distributor_id
 WHERE b.id = c.batch_id
   AND d.business_id IS NOT NULL;


-- The old allocator. Kept rather than dropped: BUSINESS_ID_COLLISION recovery notes reference it,
-- and a sequence costs nothing. Nothing calls nextval on it any more.
COMMENT ON SEQUENCE business_id_seq IS
    'Retired at V24. Business IDs are positional; see shared/businessid/PositionalId.java.';

SELECT grant_app_privileges();
