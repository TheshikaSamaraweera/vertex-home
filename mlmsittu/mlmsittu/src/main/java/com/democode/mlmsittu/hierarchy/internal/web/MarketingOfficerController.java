package com.democode.mlmsittu.hierarchy.internal.web;

import com.democode.mlmsittu.hierarchy.internal.MarketingOfficerService;
import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.shared.api.PagedResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marketing officers and the customers they look after.
 *
 * <p>The cost analysis lives in {@code reporting}, not here, even though it counts the commission
 * these officers earn — a module's internals belong to that module, and a report is a reporting
 * concern that happens to read this one's numbers.
 */
@RestController
@RequestMapping("/api/v1")
public class MarketingOfficerController {

    private final MarketingOfficerService officers;
    private final CurrentUser currentUser;

    public MarketingOfficerController(
            MarketingOfficerService officers, CurrentUser currentUser) {
        this.officers = officers;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ administration

    @GetMapping("/admin/marketing-officers")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<MarketingOfficerService.Officer> list() {
        return PagedResponse.of(officers.list());
    }

    @GetMapping("/admin/marketing-officers/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public MarketingOfficerService.Officer one(@PathVariable UUID userId) {
        return officers.get(userId);
    }

    /** The customers under one officer, as an administrator sees them. */
    @GetMapping("/admin/marketing-officers/{userId}/customers")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<MarketingOfficerService.AssignedCustomer> customers(
            @PathVariable UUID userId) {
        return PagedResponse.of(officers.customersOf(userId));
    }

    public record EnrolRequest(
            @NotNull(message = "REQUIRED") UUID userId,
            @DecimalMin(value = "0.0000", message = "MUST_NOT_BE_NEGATIVE")
                    @DecimalMax(value = "1.0000", message = "AT_MOST_ONE_HUNDRED_PERCENT")
                    BigDecimal commissionRate) {}

    /** Makes an existing account a marketing officer. */
    @PostMapping("/admin/marketing-officers")
    @PreAuthorize("hasRole('ADMIN')")
    public MarketingOfficerService.Officer enrol(@Valid @RequestBody EnrolRequest body) {
        officers.enrol(body.userId(), body.commissionRate(), currentUser.requireId());
        return officers.get(body.userId());
    }

    public record RateRequest(
            @NotNull(message = "REQUIRED")
                    @DecimalMin(value = "0.0000", message = "MUST_NOT_BE_NEGATIVE")
                    @DecimalMax(value = "1.0000", message = "AT_MOST_ONE_HUNDRED_PERCENT")
                    BigDecimal commissionRate) {}

    /**
     * Changes one officer's rate.
     *
     * <p>{@code ADMIN}, which {@code SUPER_ADMIN} implies. It decides what the business pays, so
     * it sits with the people who decide that rather than with any staff role.
     */
    @PutMapping("/admin/marketing-officers/{userId}/rate")
    @PreAuthorize("hasRole('ADMIN')")
    public MarketingOfficerService.Officer setRate(
            @PathVariable UUID userId, @Valid @RequestBody RateRequest body) {
        return officers.setRate(userId, body.commissionRate(), currentUser.requireId());
    }

    public record AssignRequest(UUID marketingOfficerId) {}

    /** Puts a customer under an officer. A null id takes them out from under one. */
    @PutMapping("/admin/distributors/{distributorId}/marketing-officer")
    @PreAuthorize("hasRole('ADMIN')")
    public void assign(
            @PathVariable UUID distributorId, @RequestBody AssignRequest body) {
        officers.assign(distributorId, body.marketingOfficerId());
    }

    // ------------------------------------------------------------------ the officer's portal

    /**
     * The signed-in officer's own record.
     *
     * <p>No id parameter anywhere in this section. An officer sees their own customers and nobody
     * else's, and the way to guarantee that is for there to be no way to ask for another's.
     */
    @GetMapping("/officer/me")
    @PreAuthorize("hasRole('MARKETING_OFFICER')")
    public MarketingOfficerService.Officer me() {
        return officers.me(currentUser.requireId());
    }

    @GetMapping("/officer/customers")
    @PreAuthorize("hasRole('MARKETING_OFFICER')")
    public PagedResponse<MarketingOfficerService.AssignedCustomer> myCustomers() {
        return PagedResponse.of(officers.customersOf(currentUser.requireId()));
    }
}
