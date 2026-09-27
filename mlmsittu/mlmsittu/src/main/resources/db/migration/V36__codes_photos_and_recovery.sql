-- ==============================================================================================
-- Six client changes, one migration.
--
-- Numbered V36, not V31. It was written as V31 on a branch that turned out to be behind: V31 to
-- V35 already existed upstream, and Flyway records one row per version — two different V31s means
-- whichever runs first wins and the other is skipped without a word. Renumbered before either
-- reached a database that had both.
--
-- Together rather than separately because every one of them is an additive column or a small
-- table, and a failure in any part should leave the database exactly as it was. One transaction
-- gives that for free; six migrations would give six chances to end up half-applied.
-- ==============================================================================================


-- ----------------------------------------------------------------------------------------------
-- 1 · Item codes are generated, not typed.
--
-- Two letters from the category and a four-digit number: BE0001. Typing them by hand produced
-- SLV-001 alongside SLV001 and slv-1, and a code that is nearly right is worse than one that is
-- wrong, because it looks fine on a label and matches nothing.
--
-- The prefix is stored on the category rather than derived per item. Derivation has to happen
-- once, when the category is created, because that is the only moment there is a clash to resolve
-- — computing it per item would give the same two letters to Bedroom and Beverages forever.
-- ----------------------------------------------------------------------------------------------
-- VARCHAR, not CHAR. CHAR is blank-padded, so a two-character value read back from a wider
-- column would carry trailing spaces into every item code — and Hibernate maps CHAR to bpchar,
-- which fails schema validation against a String field.
ALTER TABLE category ADD COLUMN code_prefix VARCHAR(2);

DO $prefixes$
DECLARE
    row_        RECORD;
    letters     TEXT;
    candidate   TEXT;
    alternative TEXT;
    found       BOOLEAN;
BEGIN
    FOR row_ IN SELECT id, name FROM category WHERE code_prefix IS NULL ORDER BY created_at, id LOOP
        -- Letters only. "3-Piece Suites" must not become "3-".
        letters := upper(regexp_replace(row_.name, '[^A-Za-z]', '', 'g'));
        IF length(letters) < 2 THEN
            letters := rpad(letters, 2, 'X');
        END IF;

        candidate := substr(letters, 1, 2);
        found := NOT EXISTS (SELECT 1 FROM category WHERE code_prefix = candidate);

        -- First letter plus each later letter of the name: Beverages, with BE taken, tries BV,
        -- BE, BR, BA, BG, BE, BS. Keeping the first letter is what makes the result still look
        -- like the category it belongs to.
        IF NOT found THEN
            FOR i IN 3..length(letters) LOOP
                alternative := substr(letters, 1, 1) || substr(letters, i, 1);
                IF NOT EXISTS (SELECT 1 FROM category WHERE code_prefix = alternative) THEN
                    candidate := alternative;
                    found := TRUE;
                    EXIT;
                END IF;
            END LOOP;
        END IF;

        -- Then the first letter with every other character, so a short name whose letters are all
        -- spoken for still gets something.
        IF NOT found THEN
            FOREACH alternative IN ARRAY string_to_array('A,B,C,D,E,F,G,H,I,J,K,L,M,N,O,P,Q,R,S,T,U,V,W,X,Y,Z,2,3,4,5,6,7,8,9', ',') LOOP
                alternative := substr(letters, 1, 1) || alternative;
                IF NOT EXISTS (SELECT 1 FROM category WHERE code_prefix = alternative) THEN
                    candidate := alternative;
                    found := TRUE;
                    EXIT;
                END IF;
            END LOOP;
        END IF;

        IF NOT found THEN
            RAISE EXCEPTION
                'No free two-character prefix for category "%". Every combination beginning with '
                '% is taken; rename it or free one up.', row_.name, substr(letters, 1, 1);
        END IF;

        UPDATE category SET code_prefix = candidate WHERE id = row_.id;
    END LOOP;
END
$prefixes$;

ALTER TABLE category ALTER COLUMN code_prefix SET NOT NULL;
ALTER TABLE category ADD CONSTRAINT uq_category_code_prefix UNIQUE (code_prefix);


-- One counter per prefix, so BE0001 and LI0001 both exist and neither has to know about the
-- other. A table rather than a PostgreSQL sequence because there is one per category and
-- sequences cannot be created from application code without DDL rights — which the runtime role
-- deliberately does not have.
CREATE TABLE item_code_sequence (
    prefix     VARCHAR(2) PRIMARY KEY,
    next_value INTEGER NOT NULL DEFAULT 1 CHECK (next_value > 0)
);

-- Existing items keep the codes they have. They are printed on labels and quoted in orders, and a
-- code is an identity rather than a formatting choice. The counters start past anything that
-- already matches the new shape, so a generated code can never collide with a hand-typed one.
INSERT INTO item_code_sequence (prefix, next_value)
SELECT upper(substr(sku, 1, 2)), max(substr(sku, 3, 4)::INTEGER) + 1
  FROM item
 WHERE sku ~ '^[A-Za-z0-9]{2}[0-9]{4}$'
 GROUP BY upper(substr(sku, 1, 2))
ON CONFLICT (prefix) DO NOTHING;


-- ----------------------------------------------------------------------------------------------
-- 2 · The back of the NIC.
--
-- A Sri Lankan identity card carries the address and the issue date on the reverse, and a reviewer
-- checking somebody against their card needs both sides.
--
-- Nullable, permanently. Registrations filed before today have one photograph and always will;
-- NOT NULL here would be a claim about the past that is not true. New submissions are required to
-- carry both, and that rule lives in RegistrationService where it can be explained in a sentence.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE registration ADD COLUMN nic_back_document_id UUID REFERENCES stored_document(id);


-- ----------------------------------------------------------------------------------------------
-- 3 · Profile photographs.
--
-- ON DELETE SET NULL: losing the picture must not delete the person.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE app_user ADD COLUMN profile_photo_id UUID REFERENCES stored_document(id) ON DELETE SET NULL;

-- A fifth document kind. Like an announcement image it is meant to be seen rather than guarded,
-- but only by somebody signed in — see the controller, which checks the kind before serving.
-- Every kind, not only the new ones. This constraint is replaced wholesale rather than added to,
-- so the list has to repeat what V32 and V33 introduced ('item_set', 'item') — leaving them out
-- would silently revoke them, and the failure surfaces only when somebody uploads a picture.
ALTER TABLE stored_document DROP CONSTRAINT IF EXISTS chk_document_kind;
ALTER TABLE stored_document
    ADD CONSTRAINT chk_document_kind
    CHECK (kind IN ('nic', 'nic_back', 'bank_slip', 'announcement',
                    'item_set', 'item', 'profile', 'other'));


-- ----------------------------------------------------------------------------------------------
-- 4 · Forgotten passwords.
--
-- Two routes, because one does not reach everybody. A customer with an email address can reset it
-- themselves; most customers here have none, so an administrator sets a temporary password and
-- the account is made to change it at the next sign-in.
--
-- The token is stored hashed. A reset token is a credential for the seconds it lives, and a
-- readable one in a table is a way into any account for anybody who can read the table — which
-- includes anybody holding a stolen backup.
-- ----------------------------------------------------------------------------------------------
CREATE TABLE password_reset_token (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_lookup ON password_reset_token (token_hash) WHERE used_at IS NULL;
CREATE INDEX idx_password_reset_user ON password_reset_token (user_id, created_at DESC);

-- Set when an administrator issues a temporary password. The next sign-in succeeds and then goes
-- nowhere except the change-password screen, so a password somebody else chose cannot become the
-- password the account keeps.
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;

SELECT grant_app_privileges();
