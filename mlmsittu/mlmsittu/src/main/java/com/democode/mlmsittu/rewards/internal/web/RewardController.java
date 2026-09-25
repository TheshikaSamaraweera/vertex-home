package com.democode.mlmsittu.rewards.internal.web;

import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.rewards.internal.RewardAdminService;
import com.democode.mlmsittu.rewards.api.RewardDelivery.ReceiveMethodChoice;
import com.democode.mlmsittu.rewards.internal.RewardAdminService.RewardEntitlementView;
import com.democode.mlmsittu.rewards.internal.RewardTrackingService;
import com.democode.mlmsittu.shared.api.PagedResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reward packs, for administrators.
 *
 * <p>Admin and super admin only, class-wide. This screen names distributors, shows what they are
 * owed, and moves stock out of a store — three separate reasons why no other role belongs here.
 */
@RestController
@RequestMapping("/api/v1/admin/rewards")
@PreAuthorize("hasRole('ADMIN')")
public class RewardController {

    private final RewardAdminService rewards;
    private final RewardTrackingService tracking;
    private final CurrentUser currentUser;

    public RewardController(
            RewardAdminService rewards, RewardTrackingService tracking, CurrentUser currentUser) {
        this.rewards = rewards;
        this.tracking = tracking;
        this.currentUser = currentUser;
    }

    /**
     * @param status {@code eligible}, {@code issued} or {@code cancelled}; omit for everything
     */
    @GetMapping
    public PagedResponse<RewardEntitlementView> list(@RequestParam(required = false) String status) {
        return PagedResponse.of(rewards.list(status));
    }

    /** The count behind the navigation badge — how many packs are waiting on somebody. */
    @GetMapping("/waiting-count")
    public WaitingCount waitingCount() {
        return new WaitingCount(rewards.countWaiting());
    }

    public record WaitingCount(long waiting) {}

    @GetMapping("/{id}")
    public RewardEntitlementView get(@PathVariable UUID id) {
        return rewards.get(id);
    }

    public record IssueRequest(
            @NotNull(message = "REQUIRED") UUID locationId, @Size(max = 500) String note) {}

    /**
     * Hands the pack over. This is where the stock actually leaves.
     *
     * <p>The store is required rather than defaulted. "Which store did these goods come out of" is
     * the question the movement has to answer afterwards, and picking one on the administrator's
     * behalf would put an answer in the ledger that nobody chose.
     */
    @PostMapping("/{id}/issue")
    public RewardEntitlementView issue(@PathVariable UUID id, @Valid @RequestBody IssueRequest body) {
        return rewards.issue(id, body.locationId(), body.note(), currentUser.requireId());
    }

    // ------------------------------------------------------------------ tracking

    /**
     * Issued packs on their way to customers.
     *
     * @param stage {@code awaiting_method}, {@code preparing}, {@code dispatched} or {@code
     *     completed}; omit for every stage
     */
    @GetMapping("/tracking")
    public PagedResponse<RewardEntitlementView> tracked(
            @RequestParam(required = false) String stage) {
        return PagedResponse.of(rewards.listTracked(stage));
    }

    /**
     * @param password the administrator's own password. Required only when changing a method that
     *     is already set.
     */
    public record ReceiveMethodRequest(
            @NotNull(message = "REQUIRED") String method,
            UUID pickupLocationId,
            @Size(max = 500) String deliveryAddress,
            @Size(max = 32) String deliveryContact,
            @Size(max = 200) String password) {}

    @PutMapping("/{id}/receive-method")
    public RewardEntitlementView setReceiveMethod(
            @PathVariable UUID id, @Valid @RequestBody ReceiveMethodRequest body) {
        return tracking.setReceiveMethod(
                id,
                new ReceiveMethodChoice(
                        body.method(),
                        body.pickupLocationId(),
                        body.deliveryAddress(),
                        body.deliveryContact()),
                body.password(),
                currentUser.requireId());
    }

    /** @param stage {@code preparing} or {@code dispatched} */
    public record StageRequest(
            @NotNull(message = "REQUIRED") String stage, @Size(max = 500) String note) {}

    @PostMapping("/{id}/stage")
    public RewardEntitlementView changeStage(
            @PathVariable UUID id, @Valid @RequestBody StageRequest body) {
        return tracking.changeStage(id, body.stage(), body.note(), currentUser.requireId());
    }

    /** @param locationId the warehouse it was handed over from — printed on the boarding pass */
    public record CompleteRequest(
            @NotNull(message = "REQUIRED") UUID locationId, @Size(max = 500) String note) {}

    /** The last stage. Final, and it closes the customer's business account. */
    @PostMapping("/{id}/complete")
    public RewardEntitlementView complete(
            @PathVariable UUID id, @Valid @RequestBody CompleteRequest body) {
        return tracking.complete(id, body.locationId(), body.note(), currentUser.requireId());
    }
}
