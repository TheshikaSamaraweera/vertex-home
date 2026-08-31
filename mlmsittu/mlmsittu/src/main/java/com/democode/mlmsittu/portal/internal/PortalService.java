package com.democode.mlmsittu.portal.internal;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import com.democode.mlmsittu.rewards.api.RewardStatus;
import com.democode.mlmsittu.identity.api.UserDirectory;
import com.democode.mlmsittu.onboarding.api.RegistrationDirectory;
import com.democode.mlmsittu.portal.api.PortalAccess;
import com.democode.mlmsittu.portal.api.PortalView;
import com.democode.mlmsittu.shared.error.ForbiddenException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a distributor sees about themselves — and the gate that decides how much of it.
 *
 * <h2>The rule</h2>
 *
 * Nothing opens until a business registration has been approved. Before that the portal shows the
 * application and its progress, and no distributor data at all, because there is none: a Business
 * ID, a place in the tree and a stage count are all created <em>by</em> approval.
 *
 * <h2>One level up, one level down</h2>
 *
 * A distributor sees their own parent and their own direct children. Not their grandparent, not
 * their grandchildren, not a sibling. That is a privacy boundary rather than a rendering
 * preference, and it is enforced here rather than in the browser: the whole downline is other
 * people's business, and the referral graph is exactly the sort of thing somebody would want to
 * scrape.
 *
 * <p>The admin views are unaffected — an administrator sees everything, through different
 * endpoints that say so.
 */
@Service
public class PortalService {

    private final UserDirectory users;
    private final RegistrationDirectory registrations;
    private final ReferralHierarchy hierarchy;
    private final RewardStatus rewards;

    public PortalService(
            UserDirectory users,
            RegistrationDirectory registrations,
            ReferralHierarchy hierarchy,
            RewardStatus rewards) {
        this.users = users;
        this.registrations = registrations;
        this.hierarchy = hierarchy;
        this.rewards = rewards;
    }

    @Transactional(readOnly = true)
    public PortalView viewFor(UUID userId) {
        UserDirectory.UserRef user =
                users.findById(userId)
                        .orElseThrow(
                                () -> new NotFoundException("USER_NOT_FOUND", "No such account."));

        Optional<RegistrationDirectory.RegistrationSnapshot> registration =
                registrations.latestFor(userId);

        Optional<DistributorNode> distributor = hierarchy.findByUserId(userId);

        PortalAccess access = accessFor(registration, distributor);

        if (access != PortalAccess.ACTIVE) {
            return locked(user, access, registration);
        }

        DistributorNode node = distributor.orElseThrow();

        // Parent, not the upline. The upline query would return the whole chain to the root, and
        // handing a distributor their entire ancestry is precisely what this boundary refuses.
        DistributorNode parent =
                node.referredBy() == null ? null : hierarchy.get(node.referredBy());

        List<DistributorNode> children = hierarchy.children(node.id());

        return new PortalView(
                PortalAccess.ACTIVE,
                user.id(),
                user.fullName(),
                user.email(),
                user.mobile(),
                user.emailVerified(),
                user.joinedAt(),
                registration.map(this::toStatus).orElse(null),
                node.id(),
                node.businessId(),
                node.approvedAt(),
                hierarchy.stageProgress(node.id()).orElse(null),
                parent,
                children,
                rewards.forDistributor(node.id()).orElse(null));
    }

    /**
     * Where they stand.
     *
     * <p>An approved distributor row is the authority, not the registration status — the two are
     * written in the same transaction at approval, and if they ever disagreed the one that decides
     * whether somebody is in the tree is the one that matters.
     */
    private PortalAccess accessFor(
            Optional<RegistrationDirectory.RegistrationSnapshot> registration,
            Optional<DistributorNode> distributor) {

        if (distributor.isPresent() && "active".equals(distributor.get().status())) {
            return PortalAccess.ACTIVE;
        }
        if (registration.isEmpty()) {
            return PortalAccess.REGISTRATION_REQUIRED;
        }

        return switch (registration.get().status()) {
            case "submitted", "under_review" -> PortalAccess.PENDING_REVIEW;
            case "resubmit_required" -> PortalAccess.CHANGES_REQUESTED;
            case "rejected" -> PortalAccess.REJECTED;
                // Approved on paper but not placed in the tree. Should be impossible — approval is
                // atomic — so it is treated as still pending rather than quietly opening the door.
            default -> PortalAccess.PENDING_REVIEW;
        };
    }

    /** The locked view: who they are and where their application is. Nothing else. */
    private PortalView locked(
            UserDirectory.UserRef user,
            PortalAccess access,
            Optional<RegistrationDirectory.RegistrationSnapshot> registration) {

        return new PortalView(
                access,
                user.id(),
                user.fullName(),
                user.email(),
                user.mobile(),
                user.emailVerified(),
                user.joinedAt(),
                registration.map(this::toStatus).orElse(null),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                // No distributor, so no entitlement. A locked view must not leak a reward the
                // account has not earned the right to be told about.
                null);
    }

    private PortalView.RegistrationStatus toStatus(
            RegistrationDirectory.RegistrationSnapshot snapshot) {
        return new PortalView.RegistrationStatus(
                snapshot.registrationId(),
                snapshot.status(),
                snapshot.submittedAt(),
                snapshot.reviewedAt(),
                snapshot.referrerBusinessId(),
                snapshot.nicLast4(),
                snapshot.rejectionReason(),
                snapshot.rejectionNote(),
                snapshot.claimed(),
                snapshot.timeline().stream()
                        .map(
                                entry ->
                                        new PortalView.TimelineEntry(
                                                entry.fromStatus(),
                                                entry.toStatus(),
                                                entry.comment(),
                                                entry.at()))
                        .toList());
    }

    /**
     * Their direct referrals, and only those.
     *
     * <p>Gated the same way the full view is, so this cannot become a side door into the tree for
     * somebody whose registration was never approved.
     */
    @Transactional(readOnly = true)
    public List<DistributorNode> directReferrals(UUID userId) {
        return hierarchy.children(requireActiveDistributor(userId));
    }

    /**
     * Refuses anything that needs an active distributor.
     *
     * <p>Used by the endpoints that return more than the summary. The summary itself is always
     * available, because a locked-out applicant still has to be told <em>why</em>.
     */
    public UUID requireActiveDistributor(UUID userId) {
        return hierarchy
                .findByUserId(userId)
                .filter(node -> "active".equals(node.status()))
                .map(DistributorNode::id)
                .orElseThrow(
                        () ->
                                new ForbiddenException(
                                        "REGISTRATION_NOT_APPROVED",
                                        "Your business registration has not been approved yet."));
    }
}
