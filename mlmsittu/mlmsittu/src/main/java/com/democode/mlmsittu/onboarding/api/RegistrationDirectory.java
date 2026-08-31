package com.democode.mlmsittu.onboarding.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only lookup of a person's business registration.
 *
 * <p>Published so the distributor portal and the admin profile can show where an application
 * stands without either of them reaching into onboarding's internals. Deliberately narrow: this
 * exposes the <em>state</em> of a registration and the reviewer's comments, and nothing that would
 * let a caller change one. Submitting, claiming, approving and rejecting stay behind
 * {@code RegistrationService}, where the locking and the self-review rule live.
 *
 * <p>Note what is <b>not</b> here: the NIC, the bank account number, and the document ids. Those
 * are read through the paths that log the access.
 */
public interface RegistrationDirectory {

    /**
     * The most recent registration this person filed, if any.
     *
     * <p>Most recent rather than the approved one: somebody rejected and asked to resubmit has
     * several, and the one that matters is the one they are working on now.
     */
    Optional<RegistrationSnapshot> latestFor(UUID userId);

    /**
     * @param status one of {@code submitted}, {@code under_review}, {@code approved},
     *     {@code rejected}, {@code resubmit_required}
     * @param claimed whether a reviewer has picked it up — the difference between "nobody has
     *     looked at this" and "somebody is looking at it", which is the question an applicant
     *     actually has
     */
    record RegistrationSnapshot(
            UUID registrationId,
            UUID userId,
            String status,
            boolean claimed,
            String referrerBusinessId,
            String nicLast4,
            String rejectionReason,
            String rejectionNote,
            Instant submittedAt,
            Instant reviewedAt,
            List<TimelineEntry> timeline) {}

    /** One step in the application's history. */
    record TimelineEntry(String fromStatus, String toStatus, String comment, Instant at) {}
}
