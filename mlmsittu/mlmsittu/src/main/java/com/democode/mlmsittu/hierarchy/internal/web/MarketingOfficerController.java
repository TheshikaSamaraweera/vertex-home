package com.democode.mlmsittu.hierarchy.internal.web;

import com.democode.mlmsittu.hierarchy.internal.MarketingOfficerService;
import com.democode.mlmsittu.identity.api.AccountRegistrar;
import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.shared.api.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
    private final AccountRegistrar accounts;

    public MarketingOfficerController(
            MarketingOfficerService officers, CurrentUser currentUser, AccountRegistrar accounts) {
        this.officers = officers;
        this.currentUser = currentUser;
        this.accounts = accounts;
    }

    // ------------------------------------------------------------------ the officer's front door

    /**
     * The public form: create an account as a marketing officer.
     *
     * <p>Open, like the distributor signup it sits beside, and answered the same way whether or not
     * the details were free — a form that says "that email is registered" is a way to ask who has an
     * account here.
     *
     * <p>What it does not do is make anybody an officer. It records an application; an administrator
     * decides. The account can sign in straight away and the portal tells them so.
     */
    @PostMapping("/officer/signup")
    public AcknowledgementResponse signUp(
            @Valid @RequestBody OfficerSignupRequest body, HttpServletRequest request) {

        accounts
                .registerSelfService(
                        body.fullName(),
                        body.email(),
                        body.mobile(),
                        body.password(),
                        "MARKETING_OFFICER",
                        request.getRemoteAddr())
                // Empty means those details are already in use. Nothing further happens — attaching
                // an application to the account that holds them would let somebody who knows an
                // email address put their own application in front of an administrator under
                // another person's name.
                .ifPresent(officers::apply);

        return new AcknowledgementResponse(
                "If those details can be registered, the account is ready to sign in. "
                        + "An administrator will review your application.");
    }

    public record OfficerSignupRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 255) String fullName,
            @Email(message = "INVALID_EMAIL") @Size(max = 320) String email,
            @Size(max = 24) String mobile,
            // Bounds only. The rule itself lives in PasswordPolicy, which every path that sets a
            // password consults.
            @NotBlank(message = "REQUIRED") @Size(max = 200) String password) {}

    public record AcknowledgementResponse(String message) {}

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

    /**
     * Everybody waiting on a decision.
     *
     * <p>Separate from the list of officers. Somebody who has applied is not an officer yet, and a
     * single list with a status column is how an applicant ends up being treated as one.
     */
    @GetMapping("/admin/marketing-officers/applications")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<MarketingOfficerService.Officer> applications() {
        return PagedResponse.of(officers.applications());
    }

    public record DecisionRequest(
            @DecimalMin(value = "0.0000", message = "MUST_NOT_BE_NEGATIVE")
                    @DecimalMax(value = "1.0000", message = "AT_MOST_ONE_HUNDRED_PERCENT")
                    BigDecimal commissionRate) {}

    /** Lets an applicant in, at a rate. Omit the rate to take the one per cent default. */
    @PostMapping("/admin/marketing-officers/{userId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public MarketingOfficerService.Officer approve(
            @PathVariable UUID userId, @Valid @RequestBody(required = false) DecisionRequest body) {
        return officers.approve(
                userId, body == null ? null : body.commissionRate(), currentUser.requireId());
    }

    public record RejectionRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 1000) String reason) {}

    /** Turns an applicant down, with a reason they are shown. */
    @PostMapping("/admin/marketing-officers/{userId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public MarketingOfficerService.Officer reject(
            @PathVariable UUID userId, @Valid @RequestBody RejectionRequest body) {
        return officers.reject(userId, body.reason(), currentUser.requireId());
    }

    public record RegisterOfficerRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 255) String fullName,
            @Email(message = "INVALID_EMAIL") @Size(max = 320) String email,
            @Size(max = 24) String mobile,
            @NotBlank(message = "REQUIRED") @Size(max = 200) String password,
            @DecimalMin(value = "0.0000", message = "MUST_NOT_BE_NEGATIVE")
                    @DecimalMax(value = "1.0000", message = "AT_MOST_ONE_HUNDRED_PERCENT")
                    BigDecimal commissionRate) {}

    /**
     * Creates an officer outright, the way an administrator registers a customer in person.
     *
     * <p>Approved immediately: the administrator doing this is the person who would have approved it.
     * A duplicate is reported rather than hidden, unlike the public form — an administrator taking
     * somebody's details across a counter needs to know they already have an account.
     */
    @PostMapping("/admin/marketing-officers/register")
    @PreAuthorize("hasRole('ADMIN')")
    public MarketingOfficerService.Officer register(@Valid @RequestBody RegisterOfficerRequest body) {
        UUID userId =
                accounts.registerForRole(
                        body.fullName(),
                        body.email(),
                        body.mobile(),
                        body.password(),
                        "MARKETING_OFFICER");

        officers.enrol(userId, body.commissionRate(), currentUser.requireId());
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
