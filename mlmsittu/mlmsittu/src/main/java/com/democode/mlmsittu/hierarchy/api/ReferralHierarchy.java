package com.democode.mlmsittu.hierarchy.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The referral graph's published surface.
 *
 * <p>Onboarding depends on this and not on the implementation. That boundary is worth keeping:
 * {@code attachToReferrer} holds a row lock and decides who is allowed into the tree, and a caller
 * that could reach past this interface could reach past the lock with it.
 */
public interface ReferralHierarchy {

    Optional<DistributorNode> findByBusinessId(String businessId);

    /**
     * Resolves a Business ID typed by an applicant, distinguishing "malformed" from "no such
     * distributor" — the two mean different things to whoever is looking at the form.
     */
    DistributorNode resolveReferrer(String rawBusinessId);

    DistributorNode get(UUID distributorId);

    /**
     * The distributor record for an account. Empty when that person has never been placed in the
     * tree — which is how the portal tells an applicant apart from a distributor.
     */
    Optional<DistributorNode> findByUserId(UUID userId);

    /** Advisory. Capacity is genuinely decided by {@link #attachToReferrer} under a row lock. */
    boolean hasCapacity(UUID referrerId);

    int maxDirectReferrals();

    /** Creates the pending row recorded at submission, before any slot is consumed. */
    UUID createPending(UUID userId, UUID referredBy);

    /**
     * Places an approved distributor in the tree and allocates its Business ID.
     *
     * @return the allocated Business ID
     * @throws com.democode.mlmsittu.shared.error.ConflictException when the referrer is at capacity
     */
    String attachToReferrer(UUID distributorId, UUID referrerId);

    void softDelete(UUID distributorId);

    /**
     * Records the item pack the distributor was approved for.
     *
     * <p>Copied onto the distributor rather than read back from the registration each time. A
     * registration is an application — it can be superseded, and a second one would make "which
     * pack did they choose" ambiguous. What they were approved for is a fact about the
     * distributor, and it is what a reward entitlement is measured against years later.
     */
    void recordChosenPack(UUID distributorId, UUID itemSetId);

    /** Enrolment stage progression (§0.2). Empty for a distributor that has not been placed yet. */
    Optional<StageProgress> stageProgress(UUID distributorId);

    List<DistributorNode> downline(UUID rootId, int depth);

    List<DistributorNode> upline(UUID distributorId);

    List<DistributorNode> children(UUID distributorId);

    List<DistributorNode> roots();

    /**
     * Every root and its descendants to a bounded depth, flat.
     *
     * <p>For drawing the tree, which needs the whole shape before it can lay anything out. The
     * expand-on-demand queries above remain the right choice everywhere else; this one exists
     * because a picture cannot be assembled a level at a time without redrawing itself on every
     * click.
     *
     * @param depth levels below each root, and the caller is expected to have capped it — an
     *     uncapped walk of a large network is exactly what §6.4 forbids
     */
    List<DistributorNode> forest(int depth);
}
