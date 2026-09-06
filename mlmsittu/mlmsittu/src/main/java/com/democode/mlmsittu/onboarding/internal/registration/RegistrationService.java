package com.democode.mlmsittu.onboarding.internal.registration;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import com.democode.mlmsittu.onboarding.internal.nic.NicProtection;
import com.democode.mlmsittu.onboarding.internal.registration.RegistrationRepository.RegistrationRow;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.ForbiddenException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business registration and its review (architecture §3, flow F-03).
 *
 * <pre>
 *   draft → submitted → under_review → { approved | rejected | resubmit_required }
 *                            ↑                                    │
 *                            └────────────────────────────────────┘
 * </pre>
 *
 * <p>Three controls here are separation-of-duties requirements rather than conveniences, and all
 * three are enforced server-side because a client-side check is not a control:
 *
 * <ul>
 *   <li><b>Claim locking</b> — one reviewer per record.
 *   <li><b>Self-review</b> — the submitter can never be the reviewer.
 *   <li><b>Atomic approval</b> — Business ID, ltree path and status all commit together or not at
 *       all, so a failure part-way cannot leave an allocated ID attached to nothing.
 * </ul>
 */
@Service
public class RegistrationService {

    private final RegistrationRepository registrations;
    private final ReferralHierarchy distributors;
    private final NicProtection nic;
    private final JdbcTemplate jdbc;

    public RegistrationService(
            RegistrationRepository registrations,
            ReferralHierarchy distributors,
            NicProtection nic,
            JdbcTemplate jdbc) {
        this.registrations = registrations;
        this.distributors = distributors;
        this.nic = nic;
        this.jdbc = jdbc;
    }

    public record SubmissionRequest(
            String nicNumber,
            UUID nicDocumentId,
            UUID slipDocumentId,
            String referrerBusinessId,
            String fullAddress,
            String bankName,
            String bankBranch,
            String bankAccountNumber,
            UUID itemSetId) {}

    // ------------------------------------------------------------------ submission

    /** P4-10. Everything is validated before anything is written. */
    @Transactional
    @Audited(action = "REGISTRATION_SUBMITTED", entityType = "registration", auditFailures = true)
    public UUID submit(UUID userId, SubmissionRequest request) {

        // No referrer means a root: somebody with nobody above them, given a Business ID of their
        // own. Only an administrator can reach this — the public form still requires a referrer,
        // because a stranger who could leave it blank could put themselves at the top of the tree.
        //
        // The identifier is allocated at approval, not here, by the same code that allocates every
        // other one. Roots take 1, 2, 3 … 9, then 10, 16, 17 — never a value a seat chain could
        // also spell. See PositionalId.
        boolean isRoot =
                request.referrerBusinessId() == null || request.referrerBusinessId().isBlank();

        DistributorNode referrer = null;
        if (!isRoot) {
            // Shape, existence and active status, each a distinct error.
            referrer = distributors.resolveReferrer(request.referrerBusinessId());

            // Advisory only — capacity is consumed at approval under a row lock, not here.
            // Checking now still saves an applicant completing a form that cannot be approved.
            if (!distributors.hasCapacity(referrer.id())) {
                ConflictException full =
                        new ConflictException(
                                "REFERRER_AT_CAPACITY",
                                "That distributor already has the maximum number of referrals.");
                full.with("referrerId", referrer.id());
                throw full;
            }
        }

        UUID identityDocumentId = storeIdentityDocument(userId, request.nicNumber());

        UUID registrationId;
        try {
            registrationId =
                    registrations.create(
                            userId,
                            isRoot
                                    ? null
                                    : com.democode.mlmsittu.shared.businessid.PositionalId
                                            .normalise(request.referrerBusinessId()),
                            isRoot ? null : referrer.id(),
                            request.fullAddress(),
                            request.bankName(),
                            request.bankBranch(),
                            request.bankAccountNumber(),
                            request.itemSetId(),
                            request.nicDocumentId(),
                            request.slipDocumentId(),
                            identityDocumentId);
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.ifConstraintIs(
                    e,
                    "idx_registration_open_per_user",
                    "REGISTRATION_ALREADY_OPEN",
                    "You already have a registration in progress.");
        }

        // The distributor row exists from submission so the referral link is recorded, but it
        // holds no Business ID and no path until approval — which is where a slot is consumed.
        // A root's pending row has no referrer, which is what makes it a root: attachToReferrer
        // sees a null parent at approval, allocates a root identifier and gives it a path with a
        // single segment.
        distributors.createPending(userId, isRoot ? null : referrer.id());

        registrations.recordEvent(registrationId, "draft", "submitted", userId, null, null);
        AuditContext.record(
                registrationId,
                null,
                Map.of("referrer", isRoot ? "(none — root)" : referrer.businessId()));
        return registrationId;
    }

    /**
     * Records the NIC as hashed and encrypted, never as plaintext.
     *
     * <p>The uniqueness decision belongs to the partial unique index, not to a prior SELECT: two
     * simultaneous submissions of the same NIC would both find nothing and both insert. Catching
     * the constraint violation is the only version of this check that is actually a check.
     */
    private UUID storeIdentityDocument(UUID userId, String nicNumber) {
        try {
            return jdbc.queryForObject(
                    """
                    INSERT INTO identity_document (user_id, nic_hash, nic_encrypted, nic_last4)
                    VALUES (?, ?, ?, ?)
                    RETURNING id
                    """,
                    UUID.class,
                    userId,
                    nic.hash(nicNumber),
                    nic.encrypt(nicNumber),
                    nic.last4(nicNumber));
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.ifConstraintIs(
                    e,
                    "idx_nic_hash_active",
                    "NIC_ALREADY_REGISTERED",
                    "That national identity number is already registered to an active account.");
        }
    }

    // ------------------------------------------------------------------ review queue

    @Transactional(readOnly = true)
    public List<RegistrationRow> queue() {
        return registrations.findQueue(List.of("submitted", "under_review", "resubmit_required"));
    }

    @Transactional(readOnly = true)
    public RegistrationRow get(UUID id) {
        return registrations.findById(id).orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public List<RegistrationRepository.EventRow> timeline(UUID id) {
        return registrations.timelineOf(id);
    }

    @Transactional(readOnly = true)
    public List<RegistrationRow> mine(UUID userId) {
        return registrations.findByUser(userId);
    }

    /** P4-11. One reviewer per record. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Audited(action = "REGISTRATION_CLAIMED", entityType = "registration", auditFailures = true)
    public RegistrationRow claim(UUID registrationId, UUID reviewerId) {
        RegistrationRow row = registrations.findForUpdate(registrationId).orElseThrow(this::notFound);
        AuditContext.record(registrationId, null, null);

        assertNotSelfReview(row, reviewerId);

        if (row.claimedBy() != null && !row.claimedBy().equals(reviewerId)) {
            ConflictException claimed =
                    new ConflictException(
                            "REGISTRATION_ALREADY_CLAIMED",
                            "Another reviewer is already working on this record.");
            claimed.with("claimedBy", row.claimedByName());
            throw claimed;
        }
        if (!List.of("submitted", "under_review", "resubmit_required").contains(row.status())) {
            throw notReviewable(row);
        }

        registrations.claim(registrationId, reviewerId);
        registrations.recordEvent(registrationId, row.status(), "under_review", reviewerId, null, null);
        return registrations.findById(registrationId).orElseThrow(this::notFound);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Audited(action = "REGISTRATION_RELEASED", entityType = "registration", auditFailures = true)
    public void release(UUID registrationId, UUID reviewerId) {
        RegistrationRow row = registrations.findForUpdate(registrationId).orElseThrow(this::notFound);
        AuditContext.record(registrationId, null, null);

        if (row.claimedBy() == null || !row.claimedBy().equals(reviewerId)) {
            throw new ForbiddenException(
                    "NOT_YOUR_CLAIM", "You have not claimed this record.");
        }
        registrations.release(registrationId);
        registrations.recordEvent(registrationId, row.status(), "submitted", reviewerId, null, null);
    }

    /**
     * P4-13. Approval, in one transaction.
     *
     * <p>The Business ID and the ltree path are allocated inside this transaction by
     * {@code ReferralHierarchy}, which takes the referrer's row lock to enforce the width cap. If
     * anything here throws — a full referrer, a constraint, a lost connection — the sequence values
     * are consumed but nothing is persisted, so there is never an allocated Business ID pointing at
     * a registration that was not approved.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Audited(action = "REGISTRATION_APPROVED", entityType = "registration", auditFailures = true)
    public ApprovalResult approve(UUID registrationId, UUID reviewerId) {
        RegistrationRow row = registrations.findForUpdate(registrationId).orElseThrow(this::notFound);
        AuditContext.record(registrationId, null, null);

        assertNotSelfReview(row, reviewerId);
        assertClaimedBy(row, reviewerId);

        UUID distributorId = pendingDistributorFor(row.userId());
        String businessId = distributors.attachToReferrer(distributorId, row.referrerDistributorId());

        // The pack travels from the application onto the distributor here, at the one moment it is
        // decided. Everything downstream — including what they are owed for completing four stages
        // — reads it from the distributor, never from the registration.
        distributors.recordChosenPack(distributorId, row.itemSetId());

        registrations.markReviewed(registrationId, "approved", reviewerId, null, null);
        registrations.recordEvent(registrationId, row.status(), "approved", reviewerId, null, null);

        AuditContext.record(
                registrationId,
                Map.of("status", row.status()),
                Map.of("status", "approved", "businessId", businessId));

        return new ApprovalResult(registrationId, distributorId, businessId);
    }

    public record ApprovalResult(UUID registrationId, UUID distributorId, String businessId) {}

    /** P4-13 reject, and the resubmit path that returns the record to the applicant. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Audited(action = "REGISTRATION_REJECTED", entityType = "registration", auditFailures = true)
    public RegistrationRow reject(
            UUID registrationId, UUID reviewerId, String reason, String note, boolean allowResubmit) {

        RegistrationRow row = registrations.findForUpdate(registrationId).orElseThrow(this::notFound);
        AuditContext.record(registrationId, null, null);

        assertNotSelfReview(row, reviewerId);
        assertClaimedBy(row, reviewerId);

        if (reason == null || reason.isBlank()) {
            // A rejection an applicant cannot act on wastes everybody's time and generates a
            // support call that has to reconstruct the reason anyway.
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "REJECTION_REASON_REQUIRED",
                    "Say why it was rejected so the applicant can fix it.");
        }

        String target = allowResubmit ? "resubmit_required" : "rejected";
        registrations.markReviewed(registrationId, target, reviewerId, reason, note);
        registrations.recordEvent(registrationId, row.status(), target, reviewerId, reason, note);

        AuditContext.record(
                registrationId,
                Map.of("status", row.status()),
                Map.of("status", target, "reason", reason));

        return registrations.findById(registrationId).orElseThrow(this::notFound);
    }

    // ------------------------------------------------------------------ guards

    /**
     * P4-12. The submitter can never review their own registration — including a {@code
     * super_admin}, who architecture §8.1 bars explicitly.
     */
    private void assertNotSelfReview(RegistrationRow row, UUID reviewerId) {
        if (row.userId().equals(reviewerId)) {
            throw new ForbiddenException(
                    "SELF_REVIEW_FORBIDDEN",
                    "You cannot review your own registration. Ask another reviewer.");
        }
    }

    private void assertClaimedBy(RegistrationRow row, UUID reviewerId) {
        if (row.claimedBy() == null || !row.claimedBy().equals(reviewerId)) {
            throw new ForbiddenException(
                    "NOT_YOUR_CLAIM", "Claim this record before deciding on it.");
        }
        if (!"under_review".equals(row.status())) {
            throw notReviewable(row);
        }
    }

    private ConflictException notReviewable(RegistrationRow row) {
        ConflictException conflict =
                new ConflictException(
                        "REGISTRATION_NOT_REVIEWABLE", "This record has already been decided.");
        conflict.with("registrationStatus", row.status());
        return conflict;
    }

    private UUID pendingDistributorFor(UUID userId) {
        return jdbc
                .query(
                        """
                        SELECT id FROM distributor
                        WHERE user_id = ? AND status = 'pending' AND deleted_at IS NULL
                        """,
                        (rs, rowNum) -> rs.getObject(1, UUID.class),
                        userId)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "PENDING_DISTRIBUTOR_MISSING",
                                        "No pending distributor for this applicant."));
    }

    private NotFoundException notFound() {
        return new NotFoundException("REGISTRATION_NOT_FOUND", "No registration with that id.");
    }
}
