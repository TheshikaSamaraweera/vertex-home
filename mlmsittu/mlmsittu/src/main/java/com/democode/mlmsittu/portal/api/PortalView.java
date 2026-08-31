package com.democode.mlmsittu.portal.api;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.rewards.api.RewardStatus;
import com.democode.mlmsittu.hierarchy.api.StageProgress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything a distributor may see about themselves, in one response.
 *
 * <h2>Why one endpoint rather than six</h2>
 *
 * The portal is gated: until a registration is approved, almost nothing should load. Six endpoints
 * would mean six places to enforce that, and the first one somebody forgets is a leak. One
 * response, assembled behind a single gate, cannot half-enforce it — the fields that must not be
 * visible are simply null.
 *
 * @param access the gate. Everything below {@code distributor} is null unless this is
 *     {@link PortalAccess#ACTIVE}.
 * @param parent who referred them. Null for a root distributor.
 * @param children their direct referrals. <b>One level only</b> — a distributor sees their own
 *     parent and their own children, and nothing further in either direction. That is a deliberate
 *     privacy boundary, not a rendering choice: the rest of the tree is other people's business.
 */
public record PortalView(
        PortalAccess access,

        // ---- always present, from the moment the account exists
        UUID userId,
        String fullName,
        String email,
        String mobile,
        boolean emailVerified,
        Instant joinedAt,

        // ---- present once something has been submitted
        RegistrationStatus registration,

        // ---- present only when ACTIVE
        UUID distributorId,
        String businessId,
        Instant approvedAt,
        StageProgress stages,
        DistributorNode parent,
        List<DistributorNode> children,

        /** Their item pack, once four stages are complete. Null until then. */
        RewardStatus.RewardSnapshot reward) {

    /**
     * The state of their application, and what the reviewer said about it.
     *
     * @param timeline every step it has been through, newest last. This is the "confirmations,
     *     pendings and comments" the client asked for — an applicant who is waiting deserves to see
     *     that somebody has picked it up.
     */
    public record RegistrationStatus(
            UUID registrationId,
            String status,
            Instant submittedAt,
            Instant reviewedAt,
            String referrerBusinessId,
            String nicLast4,
            String rejectionReason,
            String rejectionNote,
            boolean underReview,
            List<TimelineEntry> timeline) {}

    /** @param comment the reviewer's note, when they left one */
    public record TimelineEntry(
            String fromStatus, String toStatus, String comment, Instant at) {}
}
