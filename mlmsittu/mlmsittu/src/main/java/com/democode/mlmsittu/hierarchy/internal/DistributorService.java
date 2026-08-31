package com.democode.mlmsittu.hierarchy.internal;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import com.democode.mlmsittu.hierarchy.api.StageProgress;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.businessid.PositionalId;
import com.democode.mlmsittu.shared.config.SystemConfigService;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The referral tree: joining it, and the cap on how wide any node may get.
 *
 * <p>Architecture §2.2 sketches the cap as count-then-compare in the service layer. That sketch is
 * a check-then-act race, and this implementation closes it — see {@link #attachToReferrer}.
 */
@Service
public class DistributorService implements ReferralHierarchy {

    /** Used when {@code system_config} has no row, which should not happen after V11. */
    /**
     * Only used when {@code referral.max_direct} is missing from {@code system_config}, which
     * should never happen — V4 seeds it. Kept equal to the stage count on purpose: a cap below it
     * makes the last stage unreachable, and a fallback that silently disagreed with the
     * configured value would be a very hard afternoon.
     */
    private static final int FALLBACK_MAX_DIRECT = StageProgress.TOTAL_STAGES;

    private final HierarchyRepository hierarchy;
    private final SystemConfigService config;
    private final StageProgressionService stages;

    public DistributorService(
            HierarchyRepository hierarchy,
            SystemConfigService config,
            StageProgressionService stages) {
        this.hierarchy = hierarchy;
        this.config = config;
        this.stages = stages;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    @Override
    public DistributorNode get(UUID id) {
        return hierarchy.findById(id).orElseThrow(this::notFound);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DistributorNode> findByUserId(UUID userId) {
        return hierarchy.findByUserId(userId);
    }

    /**
     * Resolves a Business ID typed by an applicant.
     *
     * <p>Three distinct failures, kept distinct because they mean different things to whoever is
     * looking at the form: the shape is wrong, no such distributor, or that distributor is full.
     */
    @Transactional(readOnly = true)
    @Override
    public DistributorNode resolveReferrer(String rawBusinessId) {
        if (!PositionalId.isValid(PositionalId.normalise(rawBusinessId))) {
            // The check character already rejected this client-side. Reaching here means either a
            // direct API call or a client that skipped validation.
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BUSINESS_ID",
                    "That referrer ID is not valid. Check it against the card.");
        }

        String normalised = PositionalId.normalise(rawBusinessId);
        return hierarchy
                .findActiveByBusinessId(normalised)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "REFERRER_NOT_FOUND",
                                        "No active distributor has that ID."));
    }

    @Transactional(readOnly = true)
    @Override
    public List<DistributorNode> downline(UUID rootId, int depth) {
        DistributorNode root = hierarchy.findById(rootId).orElseThrow(this::notFound);
        if (root.path() == null) {
            // Not yet approved, so it has no place in the tree and therefore no downline.
            return List.of();
        }
        return hierarchy.descendantsOf(root.path(), Math.max(depth, 1));
    }

    @Transactional(readOnly = true)
    @Override
    public List<DistributorNode> upline(UUID id) {
        DistributorNode node = hierarchy.findById(id).orElseThrow(this::notFound);
        return node.path() == null ? List.of() : hierarchy.ancestorsOf(node.path());
    }

    @Transactional(readOnly = true)
    @Override
    public List<DistributorNode> children(UUID id) {
        return hierarchy.childrenOf(id);
    }

    @Transactional
    @Override
    public void recordChosenPack(UUID distributorId, UUID itemSetId) {
        hierarchy.setItemSet(distributorId, itemSetId);
    }

    @Transactional(readOnly = true)
    @Override
    public List<DistributorNode> roots() {
        return hierarchy.roots();
    }

    /**
     * Every root and its descendants to {@code depth}, as one flat list.
     *
     * <p>Flat rather than nested on purpose: each node already carries {@code referredBy}, so the
     * caller can assemble the shape itself, and a flat list survives a node whose parent falls
     * outside the returned set without needing a special case.
     *
     * <p>{@code depth} is measured from each root, and the caller is expected to have capped it.
     */
    @Transactional(readOnly = true)
    @Override
    public List<DistributorNode> forest(int depth) {
        List<DistributorNode> everyone = new ArrayList<>();
        for (DistributorNode root : hierarchy.roots()) {
            if (root.path() == null) {
                // Approved but unplaced. It has no subtree to walk, and dropping it would hide a
                // distributor that genuinely exists.
                everyone.add(root);
                continue;
            }
            everyone.addAll(hierarchy.descendantsOf(root.path(), Math.max(depth, 1)));
        }
        return everyone;
    }

    @Transactional(readOnly = true)
    @Override
    public int maxDirectReferrals() {
        return config.getInt(SystemConfigService.REFERRAL_MAX_DIRECT, FALLBACK_MAX_DIRECT);
    }

    /** Advisory: what a form can show before submitting. Only {@link #attachToReferrer} decides. */
    @Transactional(readOnly = true)
    @Override
    public boolean hasCapacity(UUID referrerId) {
        int cap = maxDirectReferrals();
        return hierarchy.findById(referrerId).map(node -> node.directChildCount() < cap).orElse(false);
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    @Override
    public UUID createPending(UUID userId, UUID referredBy) {
        return hierarchy.createPending(userId, referredBy);
    }

    /**
     * Places an approved distributor under its referrer, enforcing the width cap.
     *
     * <p><b>The lock is the control.</b> {@code lockParentAndCountChildren} takes
     * {@code SELECT … FOR UPDATE} on the parent, so concurrent approvals under one referrer are
     * serialised: the second waits, then reads the count the first already committed. Without it
     * both read the same number, both believe there is room, and the cap admits one child too
     * many. Nothing about that failure is visible afterwards — the tree simply has a node that
     * should not exist.
     *
     * <p>The cap is read from {@code system_config} inside the transaction, so raising it takes
     * effect on the next approval with no restart (P4-04).
     *
     * @return the allocated Business ID
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Audited(action = "DISTRIBUTOR_ACTIVATED", entityType = "distributor", auditFailures = true)
    @Override
    public String attachToReferrer(UUID distributorId, UUID referrerId) {
        DistributorNode distributor = hierarchy.findById(distributorId).orElseThrow(this::notFound);
        AuditContext.record(distributorId, null, null);

        if (distributor.businessId() != null) {
            // A Business ID is immutable for life. Re-running approval must not mint a second one.
            throw new ConflictException(
                    "DISTRIBUTOR_ALREADY_ACTIVE", "That distributor already has a Business ID.");
        }

        String parentPath = null;

        if (referrerId != null) {
            int cap = maxDirectReferrals();
            int currentChildren =
                    hierarchy
                            .lockParentAndCountChildren(referrerId)
                            .orElseThrow(
                                    () ->
                                            new NotFoundException(
                                                    "REFERRER_NOT_FOUND",
                                                    "No active distributor has that ID."));

            if (currentChildren >= cap) {
                ConflictException full =
                        new ConflictException(
                                "REFERRER_AT_CAPACITY",
                                "That distributor already has the maximum number of referrals.");
                full.with("referrerId", referrerId);
                full.with("currentChildren", currentChildren);
                full.with("maxDirect", cap);
                throw full;
            }

            parentPath = hierarchy.pathOf(referrerId);
            hierarchy.incrementChildCount(referrerId);

            // Still inside the parent's row lock taken above, which is what makes stage
            // progression race-free. Moving this outside the lock would let two approvals both
            // award the same stage — the failure the amended architecture §4.4 describes.
            stages.recordReferralGained(referrerId, distributorId);
        }

        String businessId = allocateBusinessId(referrerId);
        String segment = "n" + hierarchy.nextPathSegment();
        String path = parentPath == null ? segment : parentPath + "." + segment;

        try {
            hierarchy.activate(distributorId, businessId, path);
            // Every distributor has four stages of their own, including a root with no referrer.
            // Creating the row at activation means "how far along are they" always has an answer,
            // rather than being absent until their first referral arrives.
            stages.initialise(distributorId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // The sequence makes this impossible in normal operation. It becomes possible after a
            // restore that reset the sequence behind existing rows — and then it would reissue an
            // identifier that already belongs to somebody, which is the one thing a Business ID
            // must never do. Fail with something an operator can act on rather than a bare 500.
            ConflictException collision =
                    new ConflictException(
                            "BUSINESS_ID_COLLISION",
                            "That Business ID is already in use. The allocation sequence is behind"
                                + " the data — it must be advanced past the highest issued ID"
                                + " before any further approvals.");
            collision.with("attemptedBusinessId", businessId);
            throw collision;
        }

        AuditContext.record(
                distributorId,
                Map.of("status", "pending"),
                Map.of("status", "active", "businessId", businessId, "path", path));

        return businessId;
    }

    /**
     * Soft delete (architecture §2.3, flow F-06).
     *
     * <p>The row stays, financial records stay, and the Business ID is retired rather than freed —
     * reissuing it would make historical records ambiguous about which person they refer to. The
     * parent's child count is decremented, so a deleted child releases its slot.
     */
    @Transactional
    @Audited(action = "DISTRIBUTOR_DELETED", entityType = "distributor", auditFailures = true)
    @Override
    public void softDelete(UUID distributorId) {
        DistributorNode node = hierarchy.findById(distributorId).orElseThrow(this::notFound);
        AuditContext.record(distributorId, Map.of("status", node.status()), Map.of("status", "deleted"));

        if (node.referredBy() != null) {
            hierarchy.lockParentAndCountChildren(node.referredBy());
            hierarchy.decrementChildCount(node.referredBy());
            stages.recordReferralLost(node.referredBy(), distributorId);
        }
        hierarchy.softDelete(distributorId);
    }

    @Override
    public Optional<StageProgress> stageProgress(UUID distributorId) {
        return stages.progressOf(distributorId);
    }

    @Override
    public Optional<DistributorNode> findByBusinessId(String businessId) {
        return hierarchy.findActiveByBusinessId(PositionalId.normalise(businessId));
    }

    private NotFoundException notFound() {
        return new NotFoundException("DISTRIBUTOR_NOT_FOUND", "No distributor with that id.");
    }

    /**
     * The next free identifier under a referrer, or the next free root.
     *
     * <p>The identifier is the parent's with a seat number appended — seat 3 under parent
     * {@code 12} is {@code 123} — which is exactly what the printed cards promise, so a person
     * holding card 3 receives the identifier that card names.
     *
     * <p>Called inside the parent's row lock taken by {@code lockParentAndCountChildren}, which is
     * what makes it safe. Two approvals arriving together under one parent are serialised there,
     * so the second reads the seat the first has already committed. Without that lock both would
     * see the same free seat and mint the same identifier, and the unique index would turn a race
     * into a failed approval.
     *
     * <p>Seats vacated by a soft delete are <b>not</b> reissued; see
     * {@code HierarchyRepository.childBusinessIds}.
     */
    private String allocateBusinessId(UUID referrerId) {
        if (referrerId == null) {
            // A root is exceptional — registration always names a referrer, so these come from
            // seeding or an administrator placing the first person in a network.
            Set<String> takenRoots = new HashSet<>(hierarchy.rootBusinessIds());
            for (int position = 1; ; position++) {
                String candidate = PositionalId.root(position);
                if (!takenRoots.contains(candidate)) {
                    return candidate;
                }
            }
        }

        String parentId = hierarchy.businessIdOf(referrerId);
        if (parentId == null) {
            throw new ConflictException(
                    "REFERRER_NOT_APPROVED",
                    "That referrer has no Business ID yet, so nobody can be placed under them.");
        }

        if (PositionalId.depth(parentId) >= PositionalId.MAX_DEPTH) {
            throw new ConflictException(
                    "TREE_TOO_DEEP",
                    "The network is " + PositionalId.MAX_DEPTH + " levels deep here and cannot grow further.");
        }

        Set<String> takenSeats = new HashSet<>(hierarchy.childBusinessIds(referrerId));
        for (int seat = 1; seat <= PositionalId.SEATS; seat++) {
            String candidate = PositionalId.child(parentId, seat);
            if (!takenSeats.contains(candidate)) {
                return candidate;
            }
        }

        // Reachable even when the capacity check passed, and legitimately so.
        //
        // A seat is consumed for good the moment it is filled: the child's identifier IS the seat,
        // and reissuing it would mean two different people had been 123 at different times, with
        // printed cards and an audit trail saying so. So soft-deleting a child frees a place in the
        // count but not a seat — the place cannot be refilled.
        //
        // The counter and the seats therefore disagree after a deletion, on purpose. Saying which
        // is which matters: "at capacity" would send an administrator looking for a child to remove
        // when removing one is exactly what caused this.
        ConflictException exhausted =
                new ConflictException(
                        "SEATS_EXHAUSTED",
                        "All "
                            + PositionalId.SEATS
                            + " referral seats under this customer have been used. A seat vacated by"
                            + " a removed customer cannot be refilled, because its ID belongs to"
                            + " that customer for good.");
        exhausted.with("referrerId", referrerId);
        exhausted.with("parentBusinessId", parentId);
        throw exhausted;
    }
}
