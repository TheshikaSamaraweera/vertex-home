-- What completing the four stages is actually worth (§0.2, finally answered).
--
-- The architecture specified the mechanic's shape and left its substance open — five questions
-- were raised in August and three of them are settled by this migration:
--
--   * What does unlocking grant?  The item pack the distributor chose when they registered.
--   * What is the bonus stage?    Reaching 4/4 is what makes the pack claimable.
--   * Is money disbursed?         No. Goods, from a store, by hand. §5's "no automated
--                                 disbursement" stands untouched, and §8.1's separation of duties
--                                 is satisfied by an admin having to issue it deliberately.
--
-- Nothing here grants itself. Becoming eligible creates a row an administrator has to act on; the
-- stock does not move until they do.

-- ---------------------------------------------------------------- the chosen pack

-- Copied onto the distributor at approval rather than read from the registration every time. The
-- registration is an application — it can be superseded, and a second one would make "which pack
-- did they choose" ambiguous. What they were approved for is a fact about the distributor.
ALTER TABLE distributor ADD COLUMN item_set_id UUID REFERENCES item_set(id);

COMMENT ON COLUMN distributor.item_set_id IS
    'The item pack selected on the approved registration. Null for distributors approved before packs were recorded, and for anyone who chose none.';

UPDATE distributor d
   SET item_set_id = r.item_set_id
  FROM registration r
 WHERE r.user_id = d.user_id
   AND r.item_set_id IS NOT NULL
   AND d.item_set_id IS NULL;

-- ---------------------------------------------------------------- the entitlement

CREATE TABLE reward_entitlement (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    distributor_id          UUID NOT NULL REFERENCES distributor(id),
    item_set_id             UUID NOT NULL REFERENCES item_set(id),

    status                  VARCHAR(16) NOT NULL DEFAULT 'eligible',
    became_eligible_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    issued_at               TIMESTAMPTZ,
    issued_by               UUID REFERENCES app_user(id),
    issued_from_location_id UUID REFERENCES location(id),
    note                    VARCHAR(500),

    CONSTRAINT chk_reward_status CHECK (status IN ('eligible', 'issued', 'cancelled')),

    -- An issued pack must say who handed it over and out of which store. A row claiming goods
    -- left the building with nobody attached to it is worse than no row.
    CONSTRAINT chk_reward_issued CHECK (
        status <> 'issued'
        OR (issued_at IS NOT NULL AND issued_by IS NOT NULL AND issued_from_location_id IS NOT NULL))
);

-- One per distributor, and the database is what enforces it.
--
-- Stage completion is derived from a counted value, and §0.2's mechanic has a known check-then-act
-- race: two referral approvals for the same parent can both read "3" and both conclude they are
-- the fourth. The service takes a row lock, but a lock is a promise made in code. This index is
-- the promise made in the schema — even a second path that forgets the lock cannot issue a pack
-- twice, it gets a constraint violation instead.
CREATE UNIQUE INDEX idx_reward_one_per_distributor ON reward_entitlement (distributor_id);

CREATE INDEX idx_reward_status ON reward_entitlement (status, became_eligible_at DESC);

-- ---------------------------------------------------------------- the movement

-- Issuing a pack takes stock out of a store, and the ledger needs a name for why. Not ADJUSTMENT:
-- an adjustment means the books were wrong, and these goods genuinely left on purpose.
ALTER TABLE stock_movement DROP CONSTRAINT chk_movement_type;
ALTER TABLE stock_movement ADD CONSTRAINT chk_movement_type CHECK (movement_type IN
    ('OPENING_BALANCE', 'RECEIPT', 'ADJUSTMENT', 'FULFILMENT', 'RETURN', 'RECONCILIATION',
     'REWARD_ISSUE'));

SELECT grant_app_privileges();
