package com.democode.mlmsittu.portal.api;

/**
 * How far a distributor has got, and therefore what they may see.
 *
 * <p>The client's rule is that nothing is visible until a business registration has been approved.
 * That is not one boolean — an applicant who has been asked for a corrected document is in a very
 * different position from one who has never started, and telling them apart is the difference
 * between a portal that guides somebody through and one that just says no.
 */
public enum PortalAccess {

    /** Signed up, e-mail confirmed, nothing submitted. The only thing to do is register. */
    REGISTRATION_REQUIRED,

    /** Submitted and waiting on a decision. Read-only, and the timeline explains where it is. */
    PENDING_REVIEW,

    /** Rejected with the door left open. The reviewer's comments say what to fix. */
    CHANGES_REQUESTED,

    /** Rejected outright. No resubmission; the reason is shown. */
    REJECTED,

    /** Approved, placed in the tree, holding a Business ID. The portal opens. */
    ACTIVE,

    /**
     * Approved once, but the membership period has run out.
     *
     * <p>Distinct from {@link #REJECTED}, and the difference matters to the person reading it:
     * rejected means they were never admitted, expired means they were and their time is up. One
     * is answered by applying again, the other by renewing.
     *
     * <p>Nothing in the tree changes. They keep their Business ID and still count as their
     * referrer's referral — a lapsed membership is between this person and the business, and
     * letting it reach into somebody else's stage count would make one missed payment a third
     * party's problem. Only the portal closes.
     */
    EXPIRED
}
