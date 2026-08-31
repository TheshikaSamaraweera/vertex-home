package com.democode.mlmsittu.identity.internal.service;

import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.notify.NotificationSender;
import com.democode.mlmsittu.shared.ratelimit.api.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account creation and email verification (P4-05, flow F-02 phase one).
 *
 * <p>Architecture §3.2 requires both email and mobile confirmed before login. <b>Only email is
 * implemented</b> — the SMS provider is still an open item (§13.3), so mobile verification and its
 * OTP are deferred and a verified email alone activates the account. When SMS lands, activation
 * moves to "both channels confirmed" and this class is where that changes.
 *
 * <h2>Token handling</h2>
 *
 * The link carries a random 256-bit value; only its SHA-256 is stored. A leaked database therefore
 * yields no usable links. Tokens last 24 hours and work once.
 */
@Service
public class AccountSignupService {

    private static final Logger log = LoggerFactory.getLogger(AccountSignupService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private final AppUserRepository users;
    private final UserInserter userInserter;
    private final PasswordEncoder passwordEncoder;
    private final NotificationSender notifications;
    private final RateLimiter rateLimiter;
    private final JdbcTemplate jdbc;
    private final String appBaseUrl;

    /**
     * The one address an administrator may reuse for every walk-in account. Empty disables it.
     *
     * <p>See {@link #aliasFor(String)} for what "reuse" actually means here — the accounts do not
     * share an address, they share an inbox.
     */
    private final String sharedInbox;

    public AccountSignupService(
            AppUserRepository users,
            UserInserter userInserter,
            PasswordEncoder passwordEncoder,
            NotificationSender notifications,
            RateLimiter rateLimiter,
            JdbcTemplate jdbc,
            @Value("${app.base-url:http://localhost:5173}") String appBaseUrl,
            @Value("${onboarding.shared-inbox:}") String sharedInbox) {
        this.users = users;
        this.userInserter = userInserter;
        this.passwordEncoder = passwordEncoder;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.jdbc = jdbc;
        this.appBaseUrl = appBaseUrl;
        this.sharedInbox = sharedInbox == null ? "" : sharedInbox.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ signup

    @Transactional
    @Audited(action = "ACCOUNT_REGISTERED", entityType = "app_user", auditFailures = true)
    public void register(
            String fullName, String email, String mobile, String rawPassword, String clientIp) {

        if (!rateLimiter.tryAcquire("signup:ip:" + clientIp, 5, Duration.ofHours(1))) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "SIGNUP_RATE_LIMITED",
                    "Too many accounts created from here. Try again later.");
        }

        String normalisedEmail = email.trim().toLowerCase(Locale.ROOT);

        AppUser user = new AppUser();
        user.setEmail(normalisedEmail);
        user.setFullName(fullName.trim());
        user.setMobile(mobile == null || mobile.isBlank() ? null : mobile.trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatusValue(UserStatus.UNVERIFIED);

        // Inserted in its own transaction. A duplicate address must leave this one usable —
        // PostgreSQL aborts a transaction outright on a constraint violation, so catching it
        // inline would kill the audit write that follows. See UserInserter.
        Optional<AppUser> saved = userInserter.insertIfEmailFree(user);

        if (saved.isEmpty()) {
            // Deliberately the same outcome an attacker would see for a fresh address: the caller
            // is told "check your email" either way, so this endpoint cannot be used to discover
            // who has an account. Only the log records which happened.
            return;
        }

        grantDistributorRole(saved.get().getId());

        AuditContext.record(saved.get().getId(), null, Map.of("email", normalisedEmail));
        issueVerificationEmail(saved.get());
    }

    /**
     * Creates an account on somebody's behalf, for an administrator sitting with them.
     *
     * <p>The client asked for this because most of their distributors are not comfortable with a
     * signup form and an email link. An administrator takes their details in person and does it
     * for them.
     *
     * <p>Three deliberate differences from {@link #register}:
     *
     * <ul>
     *   <li><b>The account is already verified.</b> Sending a confirmation link to somebody who
     *       cannot use one is the exact problem this exists to solve, and the administrator has
     *       verified them by being in the room.
     *   <li><b>Not rate limited by IP.</b> The public form's limit exists to stop a stranger
     *       creating accounts in bulk; an administrator registering a dozen people in a morning is
     *       the intended use, and blocking it would be absurd.
     *   <li><b>The duplicate address is reported.</b> Public signup is deliberately vague about
     *       whether an address is taken, so it cannot be used to discover who has an account. An
     *       administrator is entitled to know, and needs to — otherwise they cannot tell whether
     *       the person is already registered.
     * </ul>
     *
     * @return the new account's id, so the caller can go straight on to their registration
     */
    /** What an administrator needs to write down and hand over. */
    public record CreatedAccount(UUID id, String email) {}

    /**
     * Creates an account for somebody who cannot create one themselves.
     *
     * <h2>People with no e-mail address</h2>
     *
     * <p>Many customers here have no e-mail at all, and an administrator registers them at the
     * desk. For those, the administrator enters the office address configured as
     * {@code onboarding.shared-inbox}, and this generates a distinct alias from it —
     * {@code office+K7M2@gmail.com} rather than {@code office@gmail.com}.
     *
     * <p>Every alias is delivered to the same real mailbox, so the office genuinely receives all of
     * it; but each account still has its own unique address, which is what login, the unique index
     * on {@code app_user.email}, verification links and the audit trail are all built on.
     *
     * <p><b>Why not simply let several accounts share one address.</b> The address is the login
     * identifier. Two accounts holding {@code office@gmail.com} makes "who is signing in?"
     * unanswerable — a password cannot disambiguate them, because two people may choose the same
     * one, and the database would have to drop a uniqueness guarantee that a great deal else
     * depends on. Aliasing gives the same convenience with none of that.
     *
     * <p>Only the configured address is treated this way. Any other repeated address still
     * conflicts, exactly as before, because a real duplicate is usually a mistake worth catching.
     *
     * @return the account id and the address actually assigned, which the caller must show to the
     *     administrator — for a shared-inbox account it is not the address they typed
     */
    @Transactional
    @Audited(action = "ACCOUNT_REGISTERED_ON_BEHALF", entityType = "app_user", auditFailures = true)
    public CreatedAccount registerOnBehalf(
            String fullName, String email, String mobile, String rawPassword) {

        String requested = email.trim().toLowerCase(Locale.ROOT);
        boolean shared = !sharedInbox.isEmpty() && requested.equals(sharedInbox);

        // A shared-inbox account gets a fresh alias per attempt; an ordinary one gets one try and
        // the usual conflict, because a duplicate there is a mistake rather than a collision.
        int attempts = shared ? ALIAS_ATTEMPTS : 1;

        for (int attempt = 0; attempt < attempts; attempt++) {
            String address = shared ? aliasFor(sharedInbox) : requested;

            AppUser user = new AppUser();
            user.setEmail(address);
            user.setFullName(fullName.trim());
            user.setMobile(mobile == null || mobile.isBlank() ? null : mobile.trim());
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setStatusValue(UserStatus.ACTIVE);
            user.setEmailVerified(true);

            Optional<AppUser> saved = userInserter.insertIfEmailFree(user);
            if (saved.isPresent()) {
                grantDistributorRole(saved.get().getId());
                AuditContext.record(
                        saved.get().getId(),
                        null,
                        Map.of(
                                "email", address,
                                "createdBy", "administrator",
                                "sharedInbox", String.valueOf(shared)));
                return new CreatedAccount(saved.get().getId(), address);
            }
        }

        if (shared) {
            // Twenty-five draws from a space of about 900,000 all colliding is not bad luck.
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "ALIAS_ALLOCATION_FAILED",
                    "Could not allocate an address for this account. Try again.");
        }

        ConflictException taken =
                new ConflictException(
                        "EMAIL_ALREADY_REGISTERED",
                        "Somebody already has an account with that email address.");
        taken.with("email", requested);
        throw taken;
    }

    private static final int ALIAS_ATTEMPTS = 25;

    /**
     * Sub-address tag characters: no O, 0, I, 1 or L.
     *
     * <p>The address gets written on a slip of paper at the desk and read back over the phone, so
     * the pairs people habitually confuse are left out.
     */
    private static final String TAG_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /**
     * {@code office@gmail.com} → {@code office+K7M2@gmail.com}.
     *
     * <p>Plus-addressing is understood by Gmail and every other major provider: everything after
     * the {@code +} is ignored for delivery, so all of these arrive in the one real inbox.
     *
     * <p>Any {@code +tag} already on the configured address is stripped first, so configuring
     * {@code office+staff@gmail.com} yields {@code office+K7M2@gmail.com} and not a second tag,
     * which some providers do not accept.
     */
    private String aliasFor(String base) {
        int at = base.lastIndexOf('@');
        String local = base.substring(0, at);
        String domain = base.substring(at);

        int plus = local.indexOf('+');
        if (plus >= 0) {
            local = local.substring(0, plus);
        }

        StringBuilder tag = new StringBuilder(4);
        for (int position = 0; position < 4; position++) {
            tag.append(TAG_ALPHABET.charAt(RANDOM.nextInt(TAG_ALPHABET.length())));
        }
        return local + "+" + tag + domain;
    }

    /** Re-sends the link. Rate limited, and silent about whether the address exists. */
    @Transactional
    public void resendVerification(String email, String clientIp) {
        if (!rateLimiter.tryAcquire("verify-resend:ip:" + clientIp, 5, Duration.ofHours(1))) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RESEND_RATE_LIMITED",
                    "Too many requests. Try again later.");
        }

        users.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .filter(user -> !user.isEmailVerified())
                .ifPresent(this::issueVerificationEmail);
    }

    /**
     * Everybody who signs up here is a distributor.
     *
     * <p>Self-signup is the distributor front door; staff accounts are created by a super admin and
     * never come through this path. Granting the role at creation is what lets the two be told
     * apart at login, and what stops a distributor landing on the staff screens.
     *
     * <p>Raw SQL rather than the role repository because this runs inside the signup transaction
     * and the mapping is a two-column join row — loading an entity graph to write one would be
     * ceremony. {@code ON CONFLICT} keeps it safe against a retry.
     */
    private void grantDistributorRole(UUID userId) {
        jdbc.update(
                """
                INSERT INTO user_role (user_id, role_id)
                SELECT ?, id FROM app_role WHERE code = 'DISTRIBUTOR'
                ON CONFLICT DO NOTHING
                """,
                userId);
    }

    private void issueVerificationEmail(AppUser user) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        // Any older token for this user stops working the moment a new one is issued — otherwise
        // "resend" quietly multiplies the number of live links to one account.
        jdbc.update(
                "UPDATE email_verification_token SET used_at = now() "
                        + "WHERE user_id = ? AND used_at IS NULL",
                user.getId());

        jdbc.update(
                """
                INSERT INTO email_verification_token (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """,
                user.getId(),
                sha256(token),
                java.sql.Timestamp.from(Instant.now().plus(TOKEN_TTL)));

        String link = appBaseUrl + "/verify-email?token=" + token;
        notifications.sendEmail(
                user.getEmail(),
                "Confirm your MLM Sittu account",
                """
                Hello %s,

                Confirm your email address to activate your account:

                    %s

                The link works once and expires in 24 hours.

                If you did not create this account, ignore this message — nothing will happen.
                """
                        .formatted(user.getFullName(), link));
    }

    // ------------------------------------------------------------------ verification

    public record VerificationResult(boolean verified, String email) {}

    /**
     * Consumes a verification link.
     *
     * <p>The token is matched by hash, marked used, and the account activated — all in one
     * transaction, so a link cannot be redeemed twice by clicking twice quickly.
     */
    @Transactional
    @Audited(action = "EMAIL_VERIFIED", entityType = "app_user", auditFailures = true)
    public VerificationResult verify(String token) {
        Optional<UUID> userId =
                jdbc
                        .query(
                                """
                                SELECT user_id FROM email_verification_token
                                WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()
                                FOR UPDATE
                                """,
                                (rs, rowNum) -> rs.getObject(1, UUID.class),
                                (Object) sha256(token))
                        .stream()
                        .findFirst();

        if (userId.isEmpty()) {
            // Unknown, already used, or expired. The caller cannot tell which — distinguishing
            // them would let someone probe which tokens once existed.
            throw new ConflictException(
                    "VERIFICATION_LINK_INVALID",
                    "That link has expired or has already been used. Request a new one.");
        }

        jdbc.update(
                "UPDATE email_verification_token SET used_at = now() "
                        + "WHERE user_id = ? AND used_at IS NULL",
                userId.get());

        AppUser user = users.findById(userId.get()).orElseThrow();
        user.setEmailVerified(true);

        // Email alone activates, because mobile verification is not built yet. When SMS lands this
        // becomes a check that both channels are confirmed.
        if (user.statusValue() == UserStatus.UNVERIFIED) {
            user.setStatusValue(UserStatus.ACTIVE);
        }
        users.save(user);

        AuditContext.record(
                user.getId(),
                Map.of("emailVerified", false),
                Map.of("emailVerified", true, "status", user.getStatus()));

        log.info("Email verified for user {}", user.getId());
        return new VerificationResult(true, user.getEmail());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
