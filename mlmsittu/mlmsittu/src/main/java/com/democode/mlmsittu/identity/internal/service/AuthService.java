package com.democode.mlmsittu.identity.internal.service;

import com.democode.mlmsittu.identity.internal.domain.AppRole;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.identity.internal.web.dto.LoginResponse;
import com.democode.mlmsittu.identity.internal.web.dto.UserSummary;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ForbiddenException;
import com.democode.mlmsittu.shared.phone.PhoneNumber;
import com.democode.mlmsittu.shared.error.RateLimitExceededException;
import com.democode.mlmsittu.shared.error.UnauthenticatedException;
import com.democode.mlmsittu.shared.ratelimit.api.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password and TOTP login (development plan P1-03, P1-05, P1-07).
 *
 * <p>Two steps, because administrators must not get a session from a password alone:
 *
 * <ol>
 *   <li>{@link #login} verifies the password and, for any role with {@code requires_mfa}, returns
 *       a challenge instead of a session.
 *   <li>{@link #completeMfa} verifies the code and only then issues the session.
 * </ol>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totp;
    private final MfaChallengeStore challenges;
    private final RateLimiter rateLimiter;
    private final SecurityContextRepository securityContextRepository;

    private final int maxAttempts;
    private final Duration window;

    /**
     * A genuine Argon2id encoding of a random value, computed once at startup.
     *
     * <p>Verified against whenever the email is unknown, so "no such account" costs roughly the
     * same as "wrong password". Encoding a random value at startup rather than pasting a literal
     * guarantees it is well-formed and matches whatever parameters the encoder is configured with.
     */
    private final String dummyHash;

    public AuthService(
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            TotpService totp,
            MfaChallengeStore challenges,
            RateLimiter rateLimiter,
            SecurityContextRepository securityContextRepository,
            @Value("${security.rate-limit.login.max-attempts:10}") int maxAttempts,
            @Value("${security.rate-limit.login.window-minutes:15}") int windowMinutes) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.totp = totp;
        this.challenges = challenges;
        this.rateLimiter = rateLimiter;
        this.securityContextRepository = securityContextRepository;
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    // ------------------------------------------------------------------ step 1: password

    // Not readOnly: the audit aspect runs inside this transaction, and PostgreSQL refuses an
    // INSERT in a read-only transaction.
    @Transactional
    @Audited(action = "LOGIN", entityType = "app_user", auditFailures = true)
    public LoginResponse login(
            String identifier,
            String rawPassword,
            HttpServletRequest request,
            HttpServletResponse response) {

        // One field, either kind of identifier. Which one it is decides the lookup, not the
        // answer: both paths end in the same "no such account" so the form cannot be used to ask
        // whether a given address or number is registered.
        boolean byPhone = PhoneNumber.looksLikePhoneNumber(identifier);
        String normalised =
                byPhone
                        ? phoneKeyFor(identifier)
                        : identifier.trim().toLowerCase(Locale.ROOT);

        String ipKey = "login:ip:" + request.getRemoteAddr();
        String accountKey = "login:account:" + normalised;

        // Both limits are checked before any password work. Throttling by account alone lets one
        // attacker sweep many accounts from one host; throttling by IP alone lets a botnet
        // grind a single account.
        //
        // Single `|` on purpose: both counters must record the attempt. With `||`, an IP already
        // at its limit would stop the account counter advancing, so an attacker could hold one
        // account permanently un-throttled by burning a single IP.
        if (!rateLimiter.tryAcquire(ipKey, maxAttempts, window)
                | !rateLimiter.tryAcquire(accountKey, maxAttempts, window)) {
            throw new RateLimitExceededException(
                    "LOGIN_RATE_LIMITED",
                    "Too many login attempts. Try again in "
                            + window.toMinutes()
                            + " minutes.");
        }

        Optional<AppUser> found =
                byPhone ? users.findByMobile(normalised) : users.findByEmail(normalised);

        // Hash a throwaway value when the account does not exist so a missing account and a wrong
        // password take comparable time. Without this, response timing enumerates valid emails.
        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            throw invalidCredentials();
        }

        AppUser user = found.get();
        AuditContext.record(user.getId(), null, null);

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (user.statusValue() != UserStatus.ACTIVE) {
            throw new ForbiddenException(
                    "ACCOUNT_NOT_ACTIVE", "This account cannot sign in at the moment.");
        }

        boolean mfaRequired = user.requiresMfa();

        if (mfaRequired && !user.isTotpEnabled()) {
            // First login for an administrator: hand out a secret and make them prove they stored
            // it. Nothing is persisted until the code checks out.
            String secret = totp.generateSecret();
            String challengeId = challenges.create(user.getId(), secret);
            return LoginResponse.enrolment(
                    challengeId, secret, totp.otpauthUri(secret, labelFor(user)));
        }

        if (mfaRequired) {
            return LoginResponse.challenge(challenges.create(user.getId(), null));
        }

        // No MFA on this account's roles — issue the session now.
        rateLimiter.reset(ipKey);
        rateLimiter.reset(accountKey);
        establishSession(user, request, response);
        return LoginResponse.authenticated(UserSummary.from(user));
    }

    // ------------------------------------------------------------------ step 2: TOTP

    @Transactional
    @Audited(action = "LOGIN_MFA", entityType = "app_user", auditFailures = true)
    public LoginResponse completeMfa(
            String challengeId,
            String code,
            HttpServletRequest request,
            HttpServletResponse response) {

        MfaChallengeStore.Challenge challenge =
                challenges
                        .attempt(challengeId)
                        .orElseThrow(
                                () ->
                                        new UnauthenticatedException(
                                                "MFA_CHALLENGE_INVALID",
                                                "This verification step has expired or been used"
                                                    + " too many times. Sign in again."));

        AppUser user =
                users.findById(challenge.userId())
                        .orElseThrow(
                                () ->
                                        new UnauthenticatedException(
                                                "MFA_CHALLENGE_INVALID",
                                                "This verification step is no longer valid."));
        AuditContext.record(user.getId(), null, null);

        String secret = challenge.isEnrolment() ? challenge.pendingSecret() : user.getTotpSecret();
        if (!totp.verify(secret, code)) {
            throw new UnauthenticatedException(
                    "INVALID_TOTP_CODE", "That code is not valid. Check your authenticator app.");
        }

        if (challenge.isEnrolment()) {
            user.setTotpSecret(secret);
            user.setTotpEnabled(true);
            users.save(user);
            log.info("TOTP enrolled for user {}", user.getId());
        }

        challenges.discard(challengeId);
        rateLimiter.reset("login:ip:" + request.getRemoteAddr());
        rateLimiter.reset("login:account:" + user.getEmail().toLowerCase(Locale.ROOT));

        establishSession(user, request, response);
        return LoginResponse.authenticated(UserSummary.from(user));
    }

    // ------------------------------------------------------------------ logout

    @Audited(action = "LOGOUT", entityType = "app_user")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser principal) {
            AuditContext.record(principal.id(), null, null);
        }

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Issues the session, rotating the session id first.
     *
     * <p>The rotation is not cosmetic: an attacker who can plant a known session id in a victim's
     * browser before login would otherwise still hold a valid id afterwards — session fixation.
     * Discarding the pre-login session closes that.
     */
    private void establishSession(
            AppUser user, HttpServletRequest request, HttpServletResponse response) {

        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        request.getSession(true);

        AuthenticatedUser principal =
                new AuthenticatedUser(
                        user.getId(),
                        user.getEmail(),
                        user.getFullName(),
                        user.getRoles().stream()
                                .map(AppRole::getCode)
                                .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));

        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private String labelFor(AppUser user) {
        // What the authenticator app shows beside the code. Email is no longer guaranteed, so
        // fall back to the number and then to the name — an entry labelled with nothing is
        // unidentifiable in an app holding several.
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            return user.getEmail();
        }
        if (user.getMobile() != null && !user.getMobile().isBlank()) {
            return user.getMobile();
        }
        return user.getFullName();
    }

    private String phoneKeyFor(String identifier) {
        // A number that cannot be read must behave exactly like one that simply is not
        // registered. Letting the exception out would answer a question the login form must not
        // answer, and would turn a typo into a 500 rather than "those details are wrong".
        try {
            String canonical = PhoneNumber.normalise(identifier);
            return canonical == null ? "" : canonical;
        } catch (IllegalArgumentException unreadable) {
            return identifier.trim();
        }
    }

    private UnauthenticatedException invalidCredentials() {
        // One message for "no such account" and "wrong password", on purpose: distinguishing them
        // turns the login form into an account-existence oracle.
        return new UnauthenticatedException(
                "INVALID_CREDENTIALS", "Email or password is incorrect.");
    }

}
