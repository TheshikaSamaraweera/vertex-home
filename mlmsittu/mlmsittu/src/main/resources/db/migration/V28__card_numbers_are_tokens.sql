-- ==============================================================================================
-- A referral card carries its own number, and that number is what a buyer redeems.
--
-- The card's code used to be the identifier the child would receive: card 3 for parent 12 read
-- 123, which is exactly the Business ID that seat was destined for. That was fine while a card
-- was a printed reference and nothing more.
--
-- It is not fine now that cards are sold. A code that is a function of the parent and a digit
-- 1-5 is guessable by anybody who has ever seen one: hold card 121 and you can write down 122
-- through 125 without buying them. Once possession of a number is what entitles somebody to join,
-- that number has to be unguessable.
--
-- So the code becomes an independent token, drawn at random, and the card stops promising a
-- particular Business ID. The buyer gets whichever seat is free when their registration is
-- approved — which is the honest thing for the paper to say, because seats are consumed in the
-- order applications are approved and not in the order cards were sold.
-- ==============================================================================================


-- ----------------------------------------------------------------------------------------------
-- 1 · Redemption.
--
-- A card is spent once. Without this the same number could be typed by five different people, and
-- the only thing stopping the fifth would be the seat limit — which would refuse them for the
-- wrong reason, long after the money changed hands.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE referral_card
    ADD COLUMN redeemed_at      TIMESTAMPTZ,
    ADD COLUMN redeemed_by      UUID REFERENCES app_user(id),
    ADD COLUMN registration_id  UUID REFERENCES registration(id);

-- Finding a card by the number somebody typed. Case-insensitive, because a number read off paper
-- and typed back is as likely to arrive lower case as upper.
CREATE UNIQUE INDEX idx_referral_card_code_lookup ON referral_card (upper(code));

-- The live cards for one parent, which is what the "are there already five out?" check counts.
CREATE INDEX idx_referral_card_unredeemed ON referral_card (batch_id) WHERE redeemed_at IS NULL;


-- ----------------------------------------------------------------------------------------------
-- 2 · Existing cards get real numbers.
--
-- Every card printed so far carries a positional code, which is now a code anybody can guess. They
-- are reissued rather than left alone: a card in somebody's hand with a guessable number on it is
-- the exact problem this migration exists to remove, and there is no way to fix the paper from
-- here. Reprint them.
--
-- The format matches CardNumber.java — eight characters from an alphabet with no O, 0, I, 1 or L,
-- because these are read aloud over a phone and written on a slip at a desk. Stored without the
-- hyphen; that is added for display, so the unique index below can be a plain one over upper().
-- ----------------------------------------------------------------------------------------------
ALTER TABLE referral_card DROP CONSTRAINT IF EXISTS referral_card_code_key;
ALTER TABLE referral_card ALTER COLUMN code TYPE VARCHAR(32);

DO $reissue$
DECLARE
    card      RECORD;
    alphabet  TEXT := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
    candidate TEXT;
    attempt   INTEGER;
BEGIN
    FOR card IN SELECT id FROM referral_card LOOP
        attempt := 0;
        LOOP
            candidate := '';
            FOR i IN 1..8 LOOP
                candidate := candidate || substr(alphabet, 1 + floor(random() * length(alphabet))::INT, 1);
            END LOOP;

            EXIT WHEN NOT EXISTS (SELECT 1 FROM referral_card WHERE upper(code) = candidate);

            attempt := attempt + 1;
            IF attempt > 50 THEN
                RAISE EXCEPTION 'Could not draw a free card number after 50 attempts.';
            END IF;
        END LOOP;

        UPDATE referral_card SET code = candidate WHERE id = card.id;
    END LOOP;
END
$reissue$;


-- ----------------------------------------------------------------------------------------------
-- 3 · The registration records which card was used.
--
-- Nullable, and permanently so: a root has no referrer and therefore no card, and registrations
-- filed before this migration have none either. A NOT NULL here would be a lie about the past.
-- The rule that an ordinary applicant must present one is enforced where it can be explained —
-- in RegistrationService, which can say so in a sentence rather than as a constraint name.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE registration ADD COLUMN referral_card_id UUID REFERENCES referral_card(id);

CREATE INDEX idx_registration_card ON registration (referral_card_id)
 WHERE referral_card_id IS NOT NULL;

SELECT grant_app_privileges();
