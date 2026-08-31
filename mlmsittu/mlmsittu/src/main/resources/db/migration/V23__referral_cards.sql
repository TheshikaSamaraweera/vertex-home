-- ==============================================================================================
-- Referral cards
--
-- A customer needs five referrals to complete all five stages and earn their pack. Rather than
-- have them read a business ID down the phone, an administrator prints five cards and hands them
-- over. Each card carries the parent's business ID, the pack on offer, and a code identifying that
-- individual card.
--
-- What this is NOT: a redemption mechanism. Registration still asks only for the referrer's
-- business ID and is unchanged by this migration. The card code exists so an administrator can
-- say which physical cards were printed, for whom, when, and by whom — not so the applicant can
-- type it in. Nothing here is on the applicant's path.
-- ==============================================================================================


-- A print run. Five cards are printed at once for one customer, so the pack and the issuing
-- administrator belong to the run rather than being repeated on every row.
CREATE TABLE referral_card_batch (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    distributor_id  UUID NOT NULL REFERENCES distributor(id),

    -- The pack the administrator decided to offer, fixed at print time because it is printed.
    --
    -- Nullable on purpose: a set can be retired, and RESTRICT would then make the catalogue
    -- unable to retire anything that had ever appeared on a card. A card that outlives its pack
    -- still needs to say what it said when it was printed, which is why the name and price are
    -- copied below rather than joined at read time.
    item_set_id     UUID REFERENCES item_set(id) ON DELETE SET NULL,

    -- Copied, not joined. A price on a printed card is a historical fact about a piece of paper
    -- somebody is holding; re-deriving it later would silently rewrite what the customer was
    -- shown when the catalogue price changes.
    item_set_name   VARCHAR(255),
    item_set_price  NUMERIC(14, 2),

    issued_by       UUID NOT NULL REFERENCES app_user(id),
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    note            VARCHAR(255)
);

CREATE INDEX idx_referral_card_batch_distributor
    ON referral_card_batch (distributor_id, issued_at DESC);


CREATE TABLE referral_card (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    batch_id    UUID NOT NULL REFERENCES referral_card_batch(id) ON DELETE CASCADE,

    -- Printed on the card. Unambiguous by construction: the generating alphabet excludes O, 0, I,
    -- 1 and L, because these are read off impact-printed paper and dictated over the phone.
    code        VARCHAR(24) NOT NULL UNIQUE,

    -- "Card 3 of 5". Stable, so a reprint of a batch produces the same numbering.
    card_number INTEGER NOT NULL CHECK (card_number > 0),

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (batch_id, card_number)
);


-- The application role has no DDL rights, so new tables are invisible to it until this runs.
-- It is append-only aware: see V8, and never replace it with a blanket GRANT.
SELECT grant_app_privileges();
