package com.democode.mlmsittu.onboarding.internal.web;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import com.democode.mlmsittu.hierarchy.api.StageProgress;
import com.democode.mlmsittu.catalogue.api.ItemSetCatalogue;
import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.onboarding.internal.registration.ReferralCardService;
import com.democode.mlmsittu.shared.error.NotFoundException;
import com.democode.mlmsittu.onboarding.internal.registration.DistributorDetailService;
import com.democode.mlmsittu.onboarding.internal.registration.DistributorDirectoryService;
import com.democode.mlmsittu.onboarding.internal.registration.RegistrationRepository;
import com.democode.mlmsittu.onboarding.internal.registration.RegistrationService;
import com.democode.mlmsittu.onboarding.internal.registration.RegistrationService.SubmissionRequest;
import com.democode.mlmsittu.shared.api.PagedResponse;
import com.democode.mlmsittu.shared.businessid.PositionalId;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Registration, the review queue, and guarded document access. */
@RestController
@RequestMapping("/api/v1")
public class OnboardingController {

    private final RegistrationService registrations;
    private final DocumentVault documents;
    private final ReferralHierarchy distributors;
    private final DistributorDetailService distributorDetails;
    private final DistributorDirectoryService distributorDirectory;
    private final ItemSetCatalogue itemSets;
    private final ReferralCardService referralCards;
    private final CurrentUser currentUser;

    public OnboardingController(
            RegistrationService registrations,
            DocumentVault documents,
            ReferralHierarchy distributors,
            DistributorDetailService distributorDetails,
            DistributorDirectoryService distributorDirectory,
            ItemSetCatalogue itemSets,
            ReferralCardService referralCards,
            CurrentUser currentUser) {
        this.distributorDirectory = distributorDirectory;
        this.registrations = registrations;
        this.documents = documents;
        this.distributors = distributors;
        this.distributorDetails = distributorDetails;
        this.itemSets = itemSets;
        this.referralCards = referralCards;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ referrer lookup

    /**
     * Checks a referrer ID before the applicant fills in the rest of the form.
     *
     * <p>The check character is validated client-side too, so a typo never reaches here. This
     * endpoint answers the questions the client cannot: does that distributor exist, and are they
     * full.
     */
    /**
     * A named record rather than a Map, so springdoc can describe it and the frontend's generated
     * types cover it. A Map response is untypeable, which quietly opts that endpoint out of the
     * drift check that makes generating types worth doing.
     */
    public record ReferrerCheckResponse(
            boolean valid,
            String reason,
            String businessId,
            String name,
            Boolean hasCapacity,
            Integer maxDirect,
            Integer currentReferrals) {}

    @GetMapping("/referrers/{businessId}")
    public ReferrerCheckResponse checkReferrer(@PathVariable String businessId) {
        if (!PositionalId.isValid(PositionalId.normalise(businessId))) {
            return new ReferrerCheckResponse(
                    false, "INVALID_BUSINESS_ID", null, null, null, null, null);
        }
        return distributors
                .findByBusinessId(businessId)
                .map(
                        node ->
                                new ReferrerCheckResponse(
                                        true,
                                        null,
                                        node.businessId(),
                                        node.fullName(),
                                        distributors.hasCapacity(node.id()),
                                        distributors.maxDirectReferrals(),
                                        node.directChildCount()))
                .orElse(new ReferrerCheckResponse(
                        false, "REFERRER_NOT_FOUND", null, null, null, null, null));
    }

    // ------------------------------------------------------------------ document access

    public record AccessTokenResponse(String token, String url, long expiresInSeconds) {}

    /**
     * Reviewers only. The access is logged here, before any bytes move.
     *
     * <p>Uploading and downloading are generic and live on {@code DocumentController}; deciding
     * that <em>this</em> person may open <em>that</em> KYC document is a judgement only onboarding
     * can make, so it stays here.
     */
    @PostMapping("/admin/documents/{documentId}/access")
    @PreAuthorize("hasAnyRole('KYC_REVIEWER', 'SUPER_ADMIN')")
    public AccessTokenResponse requestAccess(
            @PathVariable UUID documentId,
            @RequestParam(required = false) UUID subjectUserId,
            HttpServletRequest request) {

        String token =
                documents.issueAccessToken(
                        documentId, currentUser.requireId(), subjectUserId, request.getRemoteAddr());
        return new AccessTokenResponse(
                token, "/api/v1/documents/" + token, documents.tokenTtlSeconds());
    }

    // ------------------------------------------------------------------ submission

    public record SubmitRegistrationRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 20) String nicNumber,
            @NotNull(message = "REQUIRED") UUID nicDocumentId,
            @NotNull(message = "REQUIRED") UUID slipDocumentId,
            @NotBlank(message = "REQUIRED") String referrerBusinessId,
            @NotBlank(message = "REQUIRED") @Size(max = 500) String fullAddress,
            @Size(max = 255) String bankName,
            @Size(max = 255) String bankBranch,
            @Size(max = 64) String bankAccountNumber,
            UUID itemSetId) {}

    /**
     * The item packs an applicant may choose from, for the registration form.
     *
     * <p>Its own endpoint rather than the catalogue's, because the catalogue is staff-only and an
     * applicant is not staff — they have an account and nothing else. It returns a name and a
     * price and stops there: what is <em>inside</em> a pack, and how much of it is on a shelf, is
     * warehouse information and none of an applicant's business.
     */
    @GetMapping("/registrations/item-packs")
    public PagedResponse<ItemPackOption> itemPacks() {
        return PagedResponse.of(
                itemSets.findAllActive().stream()
                        .map(set -> new ItemPackOption(set.id(), set.code(), set.name(), set.setPrice()))
                        .sorted(java.util.Comparator.comparing(ItemPackOption::name))
                        .toList());
    }

    public record ItemPackOption(UUID id, String code, String name, java.math.BigDecimal price) {}

    public record SubmissionAccepted(UUID registrationId, String status) {}

    @PostMapping("/registrations")
    public ResponseEntity<SubmissionAccepted> submit(
            @Valid @RequestBody SubmitRegistrationRequest body) {

        UUID id =
                registrations.submit(
                        currentUser.requireId(),
                        new SubmissionRequest(
                                body.nicNumber(),
                                body.nicDocumentId(),
                                body.slipDocumentId(),
                                body.referrerBusinessId(),
                                body.fullAddress(),
                                body.bankName(),
                                body.bankBranch(),
                                body.bankAccountNumber(),
                                body.itemSetId()));

        // 202: accepted for review, not completed. Approval is a human decision that happens later.
        return ResponseEntity.accepted().body(new SubmissionAccepted(id, "submitted"));
    }

    @GetMapping("/registrations/mine")
    public PagedResponse<RegistrationRepository.RegistrationRow> myRegistrations() {
        return PagedResponse.of(registrations.mine(currentUser.requireId()));
    }

    // ------------------------------------------------------------------ review queue

    /**
     * Registers somebody who cannot do it themselves.
     *
     * <p>The client's distributors are largely not comfortable with a signup form and an email
     * link, so an administrator takes their details in person and does both steps for them: create
     * the account, then submit their registration against it.
     *
     * <p>Two steps rather than one endpoint, deliberately. An account and a registration are
     * separate things with separate failure modes — a duplicate email should not lose a completed
     * form, and somebody who already has an account should be able to have a registration filed
     * without a second one being created.
     *
     * <p><b>The registration still goes through review.</b> It arrives in the queue like any
     * other, gets claimed, and needs an approval — an administrator filling the form in does not
     * approve it by doing so, and the audit records who acted on whose behalf.
     */
    @PostMapping("/admin/registrations")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionAccepted submitForUser(@Valid @RequestBody OnBehalfRequest body) {
        UUID registrationId =
                registrations.submit(
                        body.userId(),
                        new RegistrationService.SubmissionRequest(
                                body.nicNumber(),
                                body.nicDocumentId(),
                                body.slipDocumentId(),
                                body.referrerBusinessId(),
                                body.fullAddress(),
                                body.bankName(),
                                body.bankBranch(),
                                body.bankAccountNumber(),
                                body.itemSetId()));
        return new SubmissionAccepted(registrationId, "submitted");
    }

    /** @param userId whose registration this is — not the administrator filling it in */
    public record OnBehalfRequest(
            @NotNull(message = "REQUIRED") UUID userId,
            @NotBlank(message = "REQUIRED") @Size(max = 20) String nicNumber,
            @NotNull(message = "REQUIRED") UUID nicDocumentId,
            @NotNull(message = "REQUIRED") UUID slipDocumentId,
            @NotBlank(message = "REQUIRED") String referrerBusinessId,
            @Size(max = 500) String fullAddress,
            @Size(max = 255) String bankName,
            @Size(max = 255) String bankBranch,
            @Size(max = 64) String bankAccountNumber,
            UUID itemSetId) {}

    @GetMapping("/admin/registrations")
    @PreAuthorize("hasAnyRole('KYC_REVIEWER', 'SUPER_ADMIN')")
    public PagedResponse<RegistrationRepository.RegistrationRow> queue() {
        return PagedResponse.of(registrations.queue());
    }

    public record RegistrationDetailResponse(
            RegistrationRepository.RegistrationRow registration,
            List<RegistrationRepository.EventRow> timeline) {}

    @GetMapping("/admin/registrations/{id}")
    @PreAuthorize("hasAnyRole('KYC_REVIEWER', 'SUPER_ADMIN')")
    public RegistrationDetailResponse detail(@PathVariable UUID id) {
        return new RegistrationDetailResponse(registrations.get(id), registrations.timeline(id));
    }

    @PostMapping("/admin/registrations/{id}/claim")
    @PreAuthorize("hasAnyRole('KYC_REVIEWER', 'SUPER_ADMIN')")
    public RegistrationRepository.RegistrationRow claim(@PathVariable UUID id) {
        return registrations.claim(id, currentUser.requireId());
    }

    @PostMapping("/admin/registrations/{id}/release")
    @PreAuthorize("hasAnyRole('KYC_REVIEWER', 'SUPER_ADMIN')")
    public ResponseEntity<Void> release(@PathVariable UUID id) {
        registrations.release(id, currentUser.requireId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Confirming a registration is an <b>admin</b> decision, at the client's request.
     *
     * <p>A reviewer still does the checking — they claim the record, open the documents, and leave
     * comments — but the act that creates a distributor, allocates a Business ID and places
     * somebody in the referral tree is reserved for an admin or super admin. That is a real
     * separation rather than a formality: the person who inspects the evidence and the person who
     * accepts it are now different people.
     */
    @PostMapping("/admin/registrations/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public RegistrationService.ApprovalResult approve(@PathVariable UUID id) {
        return registrations.approve(id, currentUser.requireId());
    }

    public record RejectRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 64) String reason,
            @Size(max = 1000) String note,
            boolean allowResubmit) {}

    /** Also admin-only, for the same reason: refusing somebody is a decision, not an inspection. */
    @PostMapping("/admin/registrations/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public RegistrationRepository.RegistrationRow reject(
            @PathVariable UUID id, @Valid @RequestBody RejectRequest body) {
        return registrations.reject(
                id, currentUser.requireId(), body.reason(), body.note(), body.allowResubmit());
    }

    // ------------------------------------------------------------------ hierarchy

    /**
     * The whole referral tree is <b>admin and super admin only</b>.
     *
     * <p>It used to be {@code STAFF}, which meant an inventory clerk or a finance officer could
     * read the entire network: who recruited whom, everybody's name, and the shape of the
     * organisation. None of those roles has any reason to know it. The commercial structure of the
     * business is not general staff information, and the client asked for it to be narrowed.
     *
     * <p>Distributors are unaffected — they never used these endpoints. The portal has its own,
     * which enforce the one-level-up, one-level-down boundary; that is a different question from
     * this one and stays where it is.
     */
    @GetMapping("/distributors/{id}/referrals")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<DistributorNode> downline(
            @PathVariable UUID id, @RequestParam(defaultValue = "2") int depth) {
        return PagedResponse.of(distributors.downline(id, Math.min(depth, 5)));
    }

    @GetMapping("/distributors/{id}/upline")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<DistributorNode> upline(@PathVariable UUID id) {
        return PagedResponse.of(distributors.upline(id));
    }

    @GetMapping("/distributors/roots")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<DistributorNode> roots() {
        return PagedResponse.of(distributors.roots());
    }

    /**
     * Every root and its descendants to a bounded depth, in one response.
     *
     * <p>A drawn tree cannot be assembled a level at a time — the layout needs to know the whole
     * shape before it can place anything, and expanding node by node would mean redrawing the
     * picture on every click. So this is the one place that departs from §6.4's expand-on-demand
     * rule, and it pays for the departure with a hard depth cap: five levels, which with four
     * referral slots each is at most 341 nodes per root.
     *
     * <p>Nodes at the cap still report {@code directChildCount}, so the drawing can say "there is
     * more below here" honestly rather than showing a leaf that is not one.
     */
    @GetMapping("/distributors/tree")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<DistributorNode> tree(@RequestParam(defaultValue = "3") int depth) {
        return PagedResponse.of(distributors.forest(Math.min(Math.max(depth, 1), 5)));
    }

    @GetMapping("/distributors/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DistributorNode distributor(@PathVariable UUID id) {
        return distributors.get(id);
    }

    /**
     * The admin detail view (P6-06): parent, direct children, and the path in a form a person can
     * read.
     *
     * <p>Documents are listed by id only. Opening one still goes through
     * {@code /admin/documents/{id}/access}, which authorises and logs it — so this screen cannot
     * become a second, unlogged way to look at somebody's NIC.
     */
    /**
     * Every distributor and applicant, as one table.
     *
     * <p>Ignores the one-level boundary the portal enforces, which is why it is admin-only: this is
     * the view that exists precisely to see the whole population.
     */
    @GetMapping("/admin/distributors")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<DistributorDirectoryService.DistributorRow> distributorDirectory(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "true") boolean includeApplicants) {
        return PagedResponse.of(distributorDirectory.list(search, includeApplicants));
    }

    @GetMapping("/distributors/{id}/detail")
    @PreAuthorize("hasAnyRole('KYC_REVIEWER', 'ADMIN', 'SUPPORT_AGENT')")
    public DistributorDetailService.DistributorDetail distributorDetail(@PathVariable UUID id) {
        return distributorDetails.detailOf(id);
    }

    /**
     * Enrolment stage progression (§0.2).
     *
     * <p>Reports how many of the four stages are complete and whether the bonus stage is
     * unlocked. It reports no entitlement, because what a stage is worth is still undefined —
     * see {@code StageRewardPolicy}.
     */
    @GetMapping("/distributors/{id}/stages")
    @PreAuthorize("hasRole('ADMIN')")
    public StageProgress stages(@PathVariable UUID id) {
        return distributors
                .stageProgress(id)
                .orElseGet(() -> new StageProgress(id, 0, StageProgress.TOTAL_STAGES, false, null));
    }

    // ------------------------------------------------------------------ referral cards

    /**
     * Prints a batch of referral cards for one customer.
     *
     * <p>Admin and super admin only, and no portal equivalent: a customer cannot print their own
     * cards, because the point of the exercise is that the company controls how many exist.
     *
     * <p>Deliberately not idempotent. Printing twice is a legitimate thing to want — paper is lost,
     * a printer jams — and the second batch is a genuinely separate set of cards with its own
     * codes, which is exactly what an administrator needs to be able to say afterwards.
     */
    @PostMapping("/admin/distributors/{id}/referral-cards")
    @PreAuthorize("hasRole('ADMIN')")
    public ReferralCardService.ReferralCardBatch issueReferralCards(
            @PathVariable UUID id, @RequestBody(required = false) IssueCardsRequest body) {
        IssueCardsRequest request = body == null ? new IssueCardsRequest(null, null, null) : body;
        return referralCards.issue(
                id, request.itemSetId(), request.count(), request.note(), currentUser.requireId());
    }

    public record IssueCardsRequest(UUID itemSetId, Integer count, @Size(max = 255) String note) {}

    /** Every batch printed for a customer, newest first. */
    @GetMapping("/admin/distributors/{id}/referral-cards")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<ReferralCardService.ReferralCardBatch> referralCardBatches(@PathVariable UUID id) {
        return PagedResponse.of(referralCards.listForDistributor(id));
    }

    /** One batch, for the print view. */
    @GetMapping("/admin/referral-cards/{batchId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ReferralCardService.ReferralCardBatch referralCardBatch(@PathVariable UUID batchId) {
        return referralCards
                .findBatch(batchId)
                .orElseThrow(() -> new NotFoundException("BATCH_NOT_FOUND", "No such card batch."));
    }
}
