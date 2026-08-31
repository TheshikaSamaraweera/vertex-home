-- §0.2 · Enrolment reward stages.
--
-- Four stages per distributor, one completed for each referral they bring in, and a bonus stage
-- unlocked when all four are complete.
--
-- WHAT THIS MIGRATION DOES AND DOES NOT COVER
--
-- It records *stage progression* — which is fully specified in §0.2 and unambiguous. It records
-- nothing about what a completed stage is worth, because that is not specified anywhere: whether
-- unlocking grants product entitlements, a payment, a discount, or something else is still an open
-- question with the client. The reward itself hangs off `StageRewardPolicy` in the service layer,
-- which is deliberately a no-op until those answers arrive.
--
-- WHY THIS IS STORED RATHER THAN COUNTED ON DEMAND
--
-- `distributor.direct_child_count` already holds the number of referrals, and stage count is
-- derived from it. The progression is still stored, for two reasons that only matter once money
-- attaches:
--
--   1. History. Once a stage confers something, "when was stage 3 unlocked, and by whom" is a
--      question with financial consequences. A counter cannot answer it.
--   2. Stability. If the width cap is ever raised, a derived-on-read figure would silently rewrite
--      everybody's past. Stored progression does not move because a setting changed.

CREATE TABLE referral_stage_progress (
    distributor_id       UUID PRIMARY KEY REFERENCES distributor(id) ON DELETE CASCADE,

    -- 0 to 4. One per referral brought in, capped at the four stages §0.2 describes.
    stages_completed     INTEGER NOT NULL DEFAULT 0
                         CHECK (stages_completed BETWEEN 0 AND 4),

    -- "If a user unlocks all 4 stages, that user is automatically eligible to unlock the bonus
    -- stage." Eligibility is automatic; what unlocking the bonus then requires or yields is one of
    -- the open questions.
    bonus_stage_eligible BOOLEAN NOT NULL DEFAULT false,

    all_stages_completed_at TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Eligibility and stage count cannot disagree.
    CONSTRAINT chk_bonus_requires_four
        CHECK (bonus_stage_eligible = (stages_completed >= 4))
);

-- ---------------------------------------------------------------------------------------------
-- Append-only history of every stage change.
--
-- The progress table above says where a distributor stands now; this says how they got there.
-- Once a stage is worth something, that distinction is the difference between an auditable reward
-- programme and one that cannot be defended when somebody disputes a payment.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE referral_stage_event (
    id                      BIGSERIAL PRIMARY KEY,
    distributor_id          UUID NOT NULL REFERENCES distributor(id),
    stage_number            INTEGER NOT NULL CHECK (stage_number BETWEEN 1 AND 4),
    event_type              VARCHAR(24) NOT NULL,

    -- The referral whose approval or removal caused this. Never null for an unlock: a stage that
    -- cannot be traced to the person who earned it is not evidence of anything.
    triggered_by_distributor_id UUID REFERENCES distributor(id),

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_stage_event_type CHECK (event_type IN ('unlocked', 'revoked'))
);

CREATE INDEX idx_stage_event_distributor
    ON referral_stage_event (distributor_id, created_at);

-- Backfill for distributors that already exist, so progression is consistent from the first run
-- rather than starting everyone at zero regardless of the referrals they already have.
INSERT INTO referral_stage_progress (distributor_id, stages_completed, bonus_stage_eligible)
SELECT d.id, LEAST(d.direct_child_count, 4), d.direct_child_count >= 4
FROM distributor d
ON CONFLICT (distributor_id) DO NOTHING;

SELECT grant_app_privileges();

-- Registered as append-only through the helper, so no future migration can hand write access back.
CREATE OR REPLACE FUNCTION grant_app_privileges() RETURNS void AS $$
DECLARE
    append_only CONSTANT TEXT[] := ARRAY[
        'audit_log',
        'stock_movement',
        'registration_event',
        'document_access_log',
        'referral_stage_event'
    ];
    target RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mlmsittu_app') THEN
        RAISE NOTICE 'Role mlmsittu_app does not exist - skipping grants.';
        RETURN;
    END IF;

    GRANT USAGE ON SCHEMA public TO mlmsittu_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mlmsittu_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mlmsittu_app;

    FOR target IN
        SELECT child.relname AS name
        FROM pg_class child
        JOIN pg_namespace ns ON ns.oid = child.relnamespace
        WHERE ns.nspname = 'public'
          AND child.relkind IN ('r', 'p')
          AND (
                child.relname = ANY (append_only)
                OR EXISTS (
                    SELECT 1 FROM pg_inherits inh
                    JOIN pg_class parent ON parent.oid = inh.inhparent
                    WHERE inh.inhrelid = child.oid AND parent.relname = ANY (append_only)
                )
          )
    LOOP
        EXECUTE format(
            'REVOKE UPDATE, DELETE, TRUNCATE ON public.%I FROM mlmsittu_app', target.name);
    END LOOP;
END;
$$ LANGUAGE plpgsql;

SELECT grant_app_privileges();
