-- ==============================================================================================
-- Marketing officers apply, and an administrator decides.
--
-- V37 gave officers a record and a rate, but the only way to become one was for an administrator
-- to enrol an existing account. The client wants a front door as well: somebody can create their
-- own account as a marketing officer, and an administrator approves it before it does anything.
--
-- Two ways in, one gate:
--
--   applied  →  an officer signed themselves up. Nothing works yet.
--   approved →  an administrator let them in, or an administrator created them outright.
--   rejected →  an administrator said no, with a reason.
--
-- An administrator creating an officer produces 'approved' directly. Sending it to a queue for
-- the same person to approve a moment later would be a step that records nothing: the decision
-- and the creation are the same act, by the same hand.
--
-- ----------------------------------------------------------------------------------------------
-- Why this is not app_user.status
--
-- An account and an application are different things, and UserStatus says so already: "an account
-- can be active while its business registration is still pending review". A customer signs up,
-- logs in, and sees a portal that tells them their registration is being reviewed. An officer now
-- does exactly the same.
--
-- Parking a pending officer as app_user.status = 'unverified' would have refused the login with
-- ACCOUNT_NOT_ACTIVE — a message that cannot tell an applicant waiting on a decision from an
-- account an administrator has suspended, and one nobody can act on.
-- ==============================================================================================


-- ----------------------------------------------------------------------------------------------
-- 1 · The state.
--
-- No DEFAULT, deliberately. Every insert names its own status, so the two ways in are visible at
-- the point they happen rather than inherited from a default somebody has to remember to override
-- — and the expensive mistake here is an officer who is live because a column defaulted that way.
--
-- Existing rows are 'approved': they were all created by an administrator enrolling an account,
-- which is the path that still produces 'approved' today.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE marketing_officer ADD COLUMN status VARCHAR(20);

UPDATE marketing_officer SET status = 'approved' WHERE status IS NULL;

ALTER TABLE marketing_officer ALTER COLUMN status SET NOT NULL;

ALTER TABLE marketing_officer
    ADD CONSTRAINT chk_marketing_officer_status
    CHECK (status IN ('applied', 'approved', 'rejected'));


-- ----------------------------------------------------------------------------------------------
-- 2 · Who decided, when, and why not.
--
-- decided_by is nullable and stays null for the rows above: nobody can be named as the approver
-- of a decision that predates the decision being recorded. Guessing an administrator would put a
-- name against an act they never performed, which is worse than an empty column.
-- ----------------------------------------------------------------------------------------------
ALTER TABLE marketing_officer
    ADD COLUMN decided_by       UUID REFERENCES app_user(id),
    ADD COLUMN decided_at       TIMESTAMPTZ,
    ADD COLUMN rejection_reason TEXT;

-- Said out loud: a rejection has a reason, and nothing else carries one. Without this the column
-- becomes a place where a note about an approved officer can sit and never be read.
ALTER TABLE marketing_officer
    ADD CONSTRAINT chk_marketing_officer_rejection_reason
    CHECK ((status = 'rejected') = (rejection_reason IS NOT NULL));


-- ----------------------------------------------------------------------------------------------
-- 3 · The queue.
--
-- Partial, because the queue is the only question asked of this column and an approved officer is
-- never in it. Oldest first: somebody who applied on Monday is seen before somebody who applied
-- this morning.
-- ----------------------------------------------------------------------------------------------
CREATE INDEX idx_marketing_officer_applied
    ON marketing_officer (created_at)
 WHERE status = 'applied';

SELECT grant_app_privileges();
