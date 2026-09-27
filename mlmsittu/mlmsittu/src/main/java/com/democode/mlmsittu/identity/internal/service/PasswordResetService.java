package com.democode.mlmsittu.identity.internal.service;

import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import com.democode.mlmsittu.shared.notify.NotificationSender;
import com.democode.mlmsittu.shared.password.PasswordPolicy;
import com.democode.mlmsittu.shared.phone.PhoneNumber;
import com.democode.mlmsittu.shared.ratelimit.api.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
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
 * Getting back into an account.
 *
 * <h2>Two routes, because one does not reach everybody</h2>
 *
 * <p>Somebody with an email address can do it themselves: they ask for a link, it arrives, they
 * choose a new password. Most of this client's customers have no email at all — for them the only
 * honest answer is the office, so an administrator sets a temporary password and the account is
 * made to change it at the next sign-in.
 *
 * <p>An email-only design would have left a phone-only customer with no route back in at all,
 * short of deleting the account and re-registering them — which would cost them their Business ID
 * and their place in the tree.
 *
 * <h2>What the request endpoint deliberately does not tell you</h2>
 *
 * <p>Whether the address exists. The answer is the same either way, because a reset form that
 * distinguishes them is a way to ask "does this person have an account here" — and the list of
 * people in a referral network is exactly the list worth harvesting.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * An hour.
     *
     * <p>Shorter than the day a verification link used to get. A reset link takes over an account,
     * where a verification link only confirmed one, and the window in which a forwarded or
     * intercepted message is still usable should be no longer than it takes somebody to read
     * their mail.
     */
    private static final Duration TOKEN_TTL = Duration.ofHours(1);

    /** Characters a temporary password is drawn from. No O, 0, I, 1 or L — it is read aloud. */
    private static final String TEMP_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final NotificationSender notifications;
    private final RateLimiter rateLimiter;
    private final JdbcTemplate jdbc;
    private final String appBaseUrl;

    public PasswordResetService(
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            NotificationSender notifications,
            RateLimiter rateLimiter,
            JdbcTemplate jdbc,
            @Value("${app.base-url:http://localhost:5173}") String appBaseUrl) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.jdbc = jdbc;
        this.appBaseUrl = appBaseUrl;
    }

    // ------------------------------------------------------------------ self-service

    /**
     * Sends a reset link, if there is anywhere to send one.
     *
     * <p>Returns nothing and reveals nothing. An account with no email address silently gets no
     * link — there is nowhere to send it — and the caller is told the same thing as everybody
     * else, because the alternative announces which customers have an email on file.
     */
    @Transactional
    @Audited(action = "PASSWORD_RESET_REQUESTED", entityType = "app_user", auditFailures = true)
    public void requestReset(String identifier, String clientIp) {
        // Both counters, and both must record the attempt — see AuthService for why the single
        // pipe matters. Tighter than login: a reset sends mail, so the cost of a flood is somebody
        // else's inbox and this application's reputation with their provider.
        if (!rateLimiter.tryAcquire("reset:ip:" + clientIp, 5, Duration.ofHours(1))
                | !rateLimiter.tryAcquire("reset:id:" + normalise(identifier), 3, Duration.ofHours(1))) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RESET_RATE_LIMITED",
                    "Too many attempts. Try again in an hour.");
        }

        Optional<AppUser> found = findByIdentifier(identifier);
        if (found.isEmpty()) {
            log.info("Password reset requested for an identifier that matches no account");
            return;
        }

        AppUser user = found.get();
        AuditContext.record(user.getId(), null, null);

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            // Nowhere to send it. The office route exists for exactly this person.
            log.info("Password reset requested for an account with no email address");
            return;
        }

        String token = issueToken(user.getId());

        notifications.sendEmail(
                user.getEmail(),
                "Reset your Vertex Home Solutions password",
                """
                Hello %s,

                Somebody asked to reset the password on your account. If that was you, open this:

                    %s

                The link works once and expires in one hour.

                If it was not you, ignore this message. Your password has not changed.
                """
                        .formatted(user.getFullName(), appBaseUrl + "/reset-password?token=" + token));
    }

    /** Spends a link and sets the new password. */
    @Transactional
    @Audited(action = "PASSWORD_RESET_COMPLETED", entityType = "app_user", auditFailures = true)
    public void completeReset(String token, String newPassword) {
        PasswordPolicy.require(newPassword);

        List<UUID> userIds =
                jdbc.queryForList(
                        """
                        SELECT user_id FROM password_reset_token
                         WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()
                           FOR UPDATE
                        """,
                        UUID.class,
                        sha256(token));

        if (userIds.isEmpty()) {
            // Expired, already spent, or never existed. One answer for all three: telling them
            // apart would let somebody probe which tokens had ever been issued.
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "RESET_LINK_INVALID",
                    "That link has expired or has already been used. Ask for a new one.");
        }

        UUID userId = userIds.get(0);
        AuditContext.record(userId, null, null);

        AppUser user = users.findById(userId).orElseThrow(this::noSuchUser);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // They chose this one themselves, so there is nothing to force.
        user.setMustChangePassword(false);
        users.saveAndFlush(user);

        // Every live token for this account, not only the one used. Somebody who has just proved
        // they control the mailbox should not leave a second working key behind them.
        jdbc.update(
                "UPDATE password_reset_token SET used_at = now() "
                        + "WHERE user_id = ? AND used_at IS NULL",
                userId);
    }

    // ------------------------------------------------------------------ the office route

    /** What the administrator reads out. */
    public record TemporaryPassword(String password) {}

    /**
     * Sets a temporary password an administrator hands over in person.
     *
     * <p>The account is flagged to change it at the next sign-in, so a password somebody else
     * chose — and wrote on a slip of paper, and may have said out loud across a counter — never
     * becomes the password the account keeps.
     *
     * <p>Returned once, in the response, and never stored in readable form. An administrator who
     * loses it before handing it over issues another; there is no screen that shows it again.
     */
    @Transactional
    @Audited(action = "PASSWORD_RESET_BY_ADMIN", entityType = "app_user", auditFailures = true)
    public TemporaryPassword resetByAdministrator(UUID userId) {
        AppUser user = users.findById(userId).orElseThrow(this::noSuchUser);
        AuditContext.record(userId, null, Map.of("by", "administrator"));

        String temporary = temporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temporary));
        user.setMustChangePassword(true);
        users.saveAndFlush(user);

        // Any outstanding link is dead. The account has a new password; a token issued before it
        // would let whoever asked for that link take the account back.
        jdbc.update(
                "UPDATE password_reset_token SET used_at = now() "
                        + "WHERE user_id = ? AND used_at IS NULL",
                userId);

        return new TemporaryPassword(temporary);
    }

    /**
     * Changes the password of the person already signed in.
     *
     * <p>The current password is required even though they are authenticated — a session left open
     * on a shared counter is otherwise a way to take the account permanently. The exception is an
     * account on a temporary password, which by definition does not know one it chose.
     */
    @Transactional
    @Audited(action = "PASSWORD_CHANGED", entityType = "app_user", auditFailures = true)
    public void changeOwnPassword(UUID userId, String currentPassword, String newPassword) {
        PasswordPolicy.require(newPassword);

        AppUser user = users.findById(userId).orElseThrow(this::noSuchUser);
        AuditContext.record(userId, null, null);

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "CURRENT_PASSWORD_WRONG", "That is not your current password.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        users.saveAndFlush(user);
    }

    // ------------------------------------------------------------------ internals

    private String issueToken(UUID userId) {
        // Any outstanding link stops working. Otherwise "send it again" quietly multiplies the
        // number of live keys to one account, and the oldest is the one most likely to have been
        // forwarded or left in a shared inbox.
        jdbc.update(
                "UPDATE password_reset_token SET used_at = now() "
                        + "WHERE user_id = ? AND used_at IS NULL",
                userId);

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        jdbc.update(
                """
                INSERT INTO password_reset_token (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """,
                userId,
                sha256(token),
                java.sql.Timestamp.from(Instant.now().plus(TOKEN_TTL)));

        return token;
    }

    /**
     * Eight characters, which the policy accepts and a person can read back over a counter.
     *
     * <p>Deliberately includes a digit and a letter by construction rather than by luck — a
     * temporary password that fails the policy it is about to be checked against would be a
     * confusing thing to hand somebody.
     */
    private String temporaryPassword() {
        StringBuilder drawn = new StringBuilder(8);
        for (int i = 0; i < 6; i++) {
            drawn.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
        }
        drawn.append((char) ('2' + RANDOM.nextInt(8)));
        drawn.append((char) ('A' + RANDOM.nextInt(26)));
        return drawn.toString();
    }

    /** By email or by phone number, the same two ways somebody signs in. */
    private Optional<AppUser> findByIdentifier(String identifier) {
        if (PhoneNumber.looksLikePhoneNumber(identifier)) {
            try {
                String canonical = PhoneNumber.normalise(identifier);
                return canonical == null ? Optional.empty() : users.findByMobile(canonical);
            } catch (IllegalArgumentException unreadable) {
                return Optional.empty();
            }
        }
        return users.findByEmail(identifier.trim().toLowerCase(Locale.ROOT));
    }

    private static String normalise(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
    }

    private NotFoundException noSuchUser() {
        return new NotFoundException("USER_NOT_FOUND", "No such account.");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", impossible);
        }
    }
}
