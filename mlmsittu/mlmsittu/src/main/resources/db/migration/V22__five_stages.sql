-- Five stages instead of four, at the client's request (28 Aug 2026).
--
-- §0.2 specified four; the business has decided a member completes their programme after **five**
-- referrals, and gets their item pack then. Nothing else about the mechanic changes — one stage
-- per approved referral, the pack issued by an administrator, no money.
--
-- Both numbers move together, and they have to: the stage count is one per referral, so a width
-- cap of four would make the fifth stage unreachable. That pairing was the whole reason the cap
-- was made runtime-configurable rather than a constraint, and this is the first time it has been
-- used in anger.

-- ---------------------------------------------------------------- the width cap

UPDATE system_config
   SET value = '5',
       updated_at = now()
 WHERE key = 'referral.max_direct';

-- ---------------------------------------------------------------- the stage ceiling

ALTER TABLE referral_stage_progress DROP CONSTRAINT chk_bonus_requires_four;
ALTER TABLE referral_stage_progress DROP CONSTRAINT referral_stage_progress_stages_completed_check;

ALTER TABLE referral_stage_progress
    ADD CONSTRAINT chk_stages_in_range CHECK (stages_completed BETWEEN 0 AND 5);

-- Anybody sitting at four is no longer complete: they have four of five and need one more. The
-- flag has to be recomputed before the constraint below can be trusted, or every existing
-- four-stage row would violate it.
--
-- Their *entitlements* are untouched. A pack already earned under the old rule is not taken back
-- because the rule changed afterwards — that would be punishing somebody for a decision they had
-- no part in, and StageRewardPolicy already says revocation leaves issued packs alone.
UPDATE referral_stage_progress
   SET bonus_stage_eligible = (stages_completed >= 5),
       all_stages_completed_at =
           CASE WHEN stages_completed >= 5 THEN all_stages_completed_at ELSE NULL END,
       updated_at = now()
 WHERE bonus_stage_eligible <> (stages_completed >= 5);

ALTER TABLE referral_stage_progress
    ADD CONSTRAINT chk_bonus_requires_all_stages
        CHECK (bonus_stage_eligible = (stages_completed >= 5));

-- ---------------------------------------------------------------- the history table

-- The append-only event log pins the stage number to 1..4 as well. Missed on the first pass, and
-- it failed loudly rather than quietly: every stage unlock threw a constraint violation the moment
-- somebody reached the fifth. Worth noting that the ceiling lives in three places — the progress
-- row, its bonus flag, and here — and all three have to move together.
ALTER TABLE referral_stage_event DROP CONSTRAINT referral_stage_event_stage_number_check;
ALTER TABLE referral_stage_event
    ADD CONSTRAINT referral_stage_event_stage_number_check
        CHECK (stage_number BETWEEN 1 AND 5);

COMMENT ON COLUMN referral_stage_progress.stages_completed IS
    '0 to 5. One per approved referral. Raised from four on 28 Aug 2026; the width cap in system_config moved with it, because a stage that cannot be reached is not a stage.';

SELECT grant_app_privileges();
