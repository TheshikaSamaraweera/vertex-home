-- ==============================================================================================
-- Marketing officers.
--
-- Numbered V37, not V32, for the same reason V36 is not V31: this was written on a branch that
-- was behind, and V32 was already taken. Flyway records one row per version, so two V32s means
-- one of them is silently skipped.
--
-- Somebody who brings customers in and is credited for the packs those customers earn. They have
-- an account and a portal of their own, like a customer, but no business registration: they are
-- not in the referral tree, they have no Business ID, and they earn no stages.
--
-- The credit is informational. The officer's portal says "you earned 1% from this customer and
-- this pack" against each one, and the cost analysis shows the same figure as a cost. Nothing
-- here records a payment, tracks a balance or marks anything settled — that is the client's
-- accounting, not this application's, and inventing half of it would be worse than leaving it
-- out.
-- ==============================================================================================


-- ----------------------------------------------------------------------------------------------
-- 1 · The role.
--
-- requires_mfa false, like DISTRIBUTOR and unlike the staff roles. An officer signs in to a
-- portal to look at their own customers; they approve nothing, see no NIC images and move no
-- stock, so an authenticator app would be a barrier without a matching risk.
--
-- It is not in the RoleHierarchy either: MARKETING_OFFICER implies nothing and nothing implies it.
-- An officer is not staff, and an administrator is not automatically an officer — being credited
-- with commission is something you are assigned, not something a bigger role includes.
-- ----------------------------------------------------------------------------------------------
INSERT INTO app_role (code, description, requires_mfa) VALUES
    ('MARKETING_OFFICER',
     'Brings customers in. Sees the customers assigned to them and what they have earned.',
     false)
ON CONFLICT (code) DO NOTHING;


-- ----------------------------------------------------------------------------------------------
-- 2 · The officer's own record.
--
-- A table rather than a column on app_user, because the rate only means anything for one role.
-- A commission_rate on every administrator and every inventory clerk would be a column that is
-- null for almost every row and answers a question nobody asks of them.
-- ----------------------------------------------------------------------------------------------
CREATE TABLE marketing_officer (
    user_id         UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,

    -- Stored as a fraction — 0.0100 is one per cent — so the arithmetic is a multiplication with
    -- no division by a hundred anywhere. Four decimal places allows a quarter of a per cent.
    commission_rate NUMERIC(6, 4) NOT NULL DEFAULT 0.0100
        CHECK (commission_rate >= 0 AND commission_rate <= 1),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID REFERENCES app_user(id)
);


-- ----------------------------------------------------------------------------------------------
-- 3 · Who looks after which customer.
--
-- On the distributor rather than a join table: a customer has one officer at a time, and a second
-- row would make "who is their officer" a question with more than one answer.
--
-- ON DELETE SET NULL. An officer leaving must not take their customers' records with them; the
-- assignment simply becomes empty and an administrator gives them to somebody else.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE distributor
    ADD COLUMN marketing_officer_id UUID REFERENCES app_user(id) ON DELETE SET NULL;

-- The officer's portal asks for exactly this: my customers, newest first.
CREATE INDEX idx_distributor_marketing_officer
    ON distributor (marketing_officer_id, created_at DESC)
 WHERE marketing_officer_id IS NOT NULL;

SELECT grant_app_privileges();
