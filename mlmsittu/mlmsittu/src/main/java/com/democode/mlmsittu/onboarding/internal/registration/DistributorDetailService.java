package com.democode.mlmsittu.onboarding.internal.registration;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import com.democode.mlmsittu.hierarchy.api.StageProgress;
import com.democode.mlmsittu.identity.api.UserDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One distributor, assembled for the admin detail view (development plan P6-06).
 *
 * <p>Lives in onboarding rather than in reporting because half the answer is registration data —
 * the KYC documents — and reaching for those from another module is exactly what the boundary rules
 * forbid. The hierarchy half comes through {@link ReferralHierarchy}, which is published.
 *
 * <p><b>Documents are listed, never fetched.</b> This returns ids; the bytes still only come out
 * through the vault's authorise-and-log path, so opening one from this screen is recorded in
 * {@code document_access_log} exactly as opening it from the review queue is. Attaching the images
 * to this response would have created a second way to read a NIC scan that no audit could see.
 */
@Service
public class DistributorDetailService {

    private final ReferralHierarchy hierarchy;
    private final RegistrationRepository registrations;
    private final UserDirectory users;
    private final DistributorDirectoryService directory;

    public DistributorDetailService(
            ReferralHierarchy hierarchy,
            RegistrationRepository registrations,
            UserDirectory users,
            DistributorDirectoryService directory) {
        this.hierarchy = hierarchy;
        this.registrations = registrations;
        this.users = users;
        this.directory = directory;
    }

    /** How many recent actions the profile shows. Enough to be useful, not a scrollback. */
    private static final int ACTIVITY_LIMIT = 50;

    /**
     * @param upline root first, ending with this distributor's parent
     * @param readablePath the upline as Business IDs — {@code SLV-00001-6 › SLV-00007-K › …}. The
     *     stored {@code path} is {@code 1.4.9} and means nothing to a person; both are returned,
     *     because §2.1 wants the raw path visible in admin views and nobody can read it.
     * @param documents present only for an approved distributor whose registration is on file
     */
    public record DistributorDetail(
            DistributorNode distributor,
            AccountSummary account,
            DistributorNode parent,
            List<DistributorNode> upline,
            List<DistributorNode> children,
            String rawPath,
            String readablePath,
            int referralsUsed,
            int referralCapacity,
            StageProgress stages,
            DistributorDocuments documents,
            List<RegistrationRepository.EventRow> registrationTimeline,
            List<DistributorDirectoryService.ActivityEntry> activity) {}

    /**
     * The account behind the distributor.
     *
     * @param joinedAt when they signed up — the joining date, which is earlier than both the
     *     registration and the approval and is the one an admin usually means
     */
    public record AccountSummary(
            UUID userId,
            String fullName,
            String email,
            String mobile,
            String status,
            boolean emailVerified,
            Instant joinedAt) {}

    /** @param registrationId null when the distributor was seeded rather than registered */
    public record DistributorDocuments(
            UUID registrationId, UUID nicDocumentId, UUID slipDocumentId, String nicLast4) {}

    @Transactional(readOnly = true)
    public DistributorDetail detailOf(UUID distributorId) {
        DistributorNode node = hierarchy.get(distributorId);

        // Root first, and it includes the node itself — drop that so "upline" means ancestors.
        List<DistributorNode> ancestors =
                hierarchy.upline(distributorId).stream()
                        .filter(ancestor -> !ancestor.id().equals(distributorId))
                        .toList();

        DistributorNode parent =
                ancestors.isEmpty() ? null : ancestors.get(ancestors.size() - 1);

        List<DistributorNode> children = hierarchy.children(distributorId);

        UserDirectory.UserRef user = users.findById(node.userId()).orElse(null);
        Optional<RegistrationRepository.RegistrationRow> registration =
                registrations.findByUser(node.userId()).stream().findFirst();

        return new DistributorDetail(
                node,
                user == null
                        ? null
                        : new AccountSummary(
                                user.id(),
                                user.fullName(),
                                user.email(),
                                user.mobile(),
                                user.status(),
                                user.emailVerified(),
                                user.joinedAt()),
                parent,
                ancestors,
                children,
                node.path(),
                readablePath(ancestors, node),
                node.directChildCount(),
                hierarchy.maxDirectReferrals(),
                hierarchy.stageProgress(distributorId).orElse(null),
                documentsFor(node.userId()).orElse(null),
                registration
                        .map(row -> registrations.timelineOf(row.id()))
                        .orElseGet(java.util.List::of),
                directory.activityOf(node.userId(), ACTIVITY_LIMIT));
    }

    /** Business IDs from the root down, so the chain reads the way somebody would say it aloud. */
    private String readablePath(List<DistributorNode> ancestors, DistributorNode node) {
        return java.util.stream.Stream.concat(ancestors.stream(), java.util.stream.Stream.of(node))
                .map(step -> step.businessId() == null ? "(pending)" : step.businessId())
                .collect(Collectors.joining(" › "));
    }

    /**
     * The most recent registration this person filed.
     *
     * <p>Most recent rather than the approved one: a rejected-then-resubmitted applicant has
     * several, and the reviewer looking at this screen wants the documents that are current.
     */
    private Optional<DistributorDocuments> documentsFor(UUID userId) {
        return registrations.findByUser(userId).stream()
                .findFirst()
                .map(
                        row ->
                                new DistributorDocuments(
                                        row.id(),
                                        row.nicDocumentId(),
                                        row.slipDocumentId(),
                                        row.nicLast4()));
    }
}
