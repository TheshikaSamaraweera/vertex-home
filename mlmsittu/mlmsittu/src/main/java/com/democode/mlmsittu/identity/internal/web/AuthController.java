package com.democode.mlmsittu.identity.internal.web;

import com.democode.mlmsittu.identity.internal.service.AccountSignupService;
import com.democode.mlmsittu.identity.internal.service.AuthService;
import com.democode.mlmsittu.identity.internal.service.AuthenticatedUser;
import com.democode.mlmsittu.identity.internal.service.UserAdminService;
import com.democode.mlmsittu.identity.internal.web.dto.LoginRequest;
import com.democode.mlmsittu.identity.internal.web.dto.LoginResponse;
import com.democode.mlmsittu.identity.internal.web.dto.TotpLoginRequest;
import com.democode.mlmsittu.identity.internal.web.dto.UserSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.democode.mlmsittu.identity.internal.service.PasswordResetService;
import com.democode.mlmsittu.identity.api.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResets;
    private final CurrentUser currentUser;
    private final UserAdminService userAdminService;
    private final AccountSignupService signupService;

    public AuthController(
            AuthService authService,
            UserAdminService userAdminService,
            AccountSignupService signupService,
            PasswordResetService passwordResets,
            CurrentUser currentUser) {
        this.passwordResets = passwordResets;
        this.currentUser = currentUser;
        this.authService = authService;
        this.userAdminService = userAdminService;
        this.signupService = signupService;
    }

    // ------------------------------------------------------------------ signup

    /**
     * {@code email} and {@code mobile} are both optional here, and exactly one of them being
     * required is not something bean validation can express across two fields. That rule lives in
     * {@code AccountSignupService.Identifiers}, which is also where the phone number is put into
     * canonical form — one place, so the desk and the login form cannot disagree about what
     * counts as the same number.
     */
    public record RegisterRequest(
            @jakarta.validation.constraints.NotBlank(message = "REQUIRED")
                    @jakarta.validation.constraints.Size(max = 255) String fullName,
            @jakarta.validation.constraints.Email(message = "INVALID_EMAIL")
                    @jakarta.validation.constraints.Size(max = 320) String email,
            @jakarta.validation.constraints.Size(max = 24) String mobile,
            @jakarta.validation.constraints.NotBlank(message = "REQUIRED")
                    // Bounds only, as a cheap guard against an absurd payload. The actual rule
                    // lives in PasswordPolicy, which every path that sets a password consults —
                    // an annotation here and there is how a policy ends up not applying to the
                    // one route somebody actually uses.
                    @jakarta.validation.constraints.Size(max = 200) String password) {}

    public record AcknowledgementResponse(String message) {}

    /**
     * Creates an account, ready to sign in.
     *
     * <p>Always the same answer, whether or not the identifier was already taken. Saying "that
     * email is registered" turns this endpoint into a way to discover who has an account.
     */
    @PostMapping("/register")
    public AcknowledgementResponse register(
            @Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        signupService.register(
                body.fullName(), body.email(), body.mobile(), body.password(),
                request.getRemoteAddr());
        return new AcknowledgementResponse(
                "If those details can be registered, the account is ready to sign in.");
    }


    /**
     * Step one. Returns a session for accounts without MFA, or a challenge for administrators.
     *
     * <p>Always 200 on a valid password, even when more is required — the status code describes
     * the request, and "your password was right but you are not in yet" is not an error.
     */
// ------------------------------------------------------------------ forgotten passwords

    public record ResetRequest(
            @jakarta.validation.constraints.NotBlank(message = "REQUIRED")
                    @jakarta.validation.constraints.Size(max = 320)
                    String identifier) {}

    /**
     * Asks for a reset link.
     *
     * <p>Always the same answer. Whether the identifier matches an account, and whether that
     * account has an email address to send to, are both things this must not reveal — a reset
     * form that distinguishes them is a way to ask who has an account here, and the membership of
     * a referral network is exactly the list worth harvesting.
     */
    @PostMapping("/forgot-password")
    public AcknowledgementResponse forgotPassword(
            @Valid @RequestBody ResetRequest body, HttpServletRequest request) {
        passwordResets.requestReset(body.identifier(), request.getRemoteAddr());
        return new AcknowledgementResponse(
                "If that account exists and has an email address, a reset link is on its way. "
                        + "If not, ask the office to reset it for you.");
    }

    public record CompleteResetRequest(
            @jakarta.validation.constraints.NotBlank(message = "REQUIRED") String token,
            @jakarta.validation.constraints.NotBlank(message = "REQUIRED") String password) {}

    @PostMapping("/reset-password")
    public AcknowledgementResponse resetPassword(@Valid @RequestBody CompleteResetRequest body) {
        passwordResets.completeReset(body.token(), body.password());
        return new AcknowledgementResponse("Your password has been changed. Sign in with it.");
    }

    public record ChangePasswordRequest(
            @jakarta.validation.constraints.NotBlank(message = "REQUIRED") String currentPassword,
            @jakarta.validation.constraints.NotBlank(message = "REQUIRED") String newPassword) {}

    /** Changing your own password while signed in. */
    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public AcknowledgementResponse changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        passwordResets.changeOwnPassword(
                currentUser.requireId(), body.currentPassword(), body.newPassword());
        return new AcknowledgementResponse("Your password has been changed.");
    }

        @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return authService.login(body.identifier(), body.password(), request, response);
    }

    /** Step two: the six-digit code. On success the session cookie is set. */
    @PostMapping("/login/totp")
    public LoginResponse completeTotp(
            @Valid @RequestBody TotpLoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return authService.completeMfa(body.challengeId(), body.code(), request, response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request, HttpServletResponse response) {
        authService.logout(request, response);
        return ResponseEntity.noContent().build();
    }

    /** Who am I, according to the session cookie you just sent. */
    @GetMapping("/me")
    public UserSummary me(@AuthenticationPrincipal AuthenticatedUser principal) {
        // Read through to the database rather than trusting the session snapshot: a role revoked
        // a minute ago should show as revoked here.
        return userAdminService.getUser(principal.id());
    }
}
