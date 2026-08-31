package com.democode.mlmsittu.identity.internal.web;

import com.democode.mlmsittu.identity.internal.service.AuthenticatedUser;
import com.democode.mlmsittu.identity.internal.service.AccountSignupService;
import com.democode.mlmsittu.identity.internal.service.UserAdminService;
import com.democode.mlmsittu.identity.internal.web.dto.UpdateRolesRequest;
import com.democode.mlmsittu.identity.internal.web.dto.UserSummary;
import com.democode.mlmsittu.shared.api.PagedResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration.
 *
 * <p><b>Super admin by default</b> (architecture §8.1) — granting and removing roles is the
 * sharpest privilege in the system and stays with one role.
 *
 * <p>One endpoint deliberately relaxes that: creating an account for somebody who cannot create
 * one themselves is open to {@code ADMIN}, because it grants no privilege at all and restricting
 * it would mean only a super admin could register a walk-in. Spring applies the method-level
 * {@code @PreAuthorize} in preference to the class-level one, so the relaxation is confined to
 * exactly that method.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class UserAdminController {

    private final UserAdminService userAdminService;
    private final AccountSignupService signups;

    public UserAdminController(UserAdminService userAdminService, AccountSignupService signups) {
        this.userAdminService = userAdminService;
        this.signups = signups;
    }

    /** Returns the frozen envelope of P0-05. Real cursors arrive in Phase 7; the shape does not. */
    @GetMapping
    public PagedResponse<UserSummary> list() {
        return PagedResponse.of(userAdminService.listUsers());
    }

    @GetMapping("/{id}")
    public UserSummary get(@PathVariable UUID id) {
        return userAdminService.getUser(id);
    }

    @PutMapping("/{id}/roles")
    public UserSummary replaceRoles(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRolesRequest body,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        return userAdminService.replaceRoles(id, body.roleCodes(), actor.id());
    }

    /**
     * Creates an account for somebody who cannot create one themselves.
     *
     * <p>Open to {@code ADMIN}, not only super admin: the roles screen next door is about granting
     * privilege and stays restricted, while this creates an ordinary distributor account with no
     * powers at all. Conflating the two would mean nobody but a super admin could register a
     * walk-in customer.
     *
     * <p>The account is created already verified — see
     * {@code AccountSignupService.registerOnBehalf} for why that is the point rather than a
     * shortcut.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedUser createOnBehalf(@Valid @RequestBody CreateUserRequest body) {
        // The returned address is the authority, not the one that was typed. For a customer with
        // no e-mail of their own the administrator enters the office address and gets back a
        // generated alias of it — that alias is the login, and it has to reach the screen.
        AccountSignupService.CreatedAccount created =
                signups.registerOnBehalf(
                        body.fullName(), body.email(), body.mobile(), body.password());
        return new CreatedUser(created.id(), created.email());
    }

    /**
     * @param password a temporary one the administrator gives the person; there is no self-service
     *     reset yet, so it is worth writing down at the desk
     */
    public record CreateUserRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 255) String fullName,
            @NotBlank(message = "REQUIRED") @Email(message = "INVALID_EMAIL") @Size(max = 320)
                    String email,
            @Size(max = 32) String mobile,
            @NotBlank(message = "REQUIRED") @Size(min = 12, max = 128) String password) {}

    public record CreatedUser(UUID id, String email) {}
}