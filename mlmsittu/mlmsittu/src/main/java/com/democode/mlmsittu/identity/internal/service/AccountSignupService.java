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
import com.democode.mlmsittu.shared.phone.PhoneNumber;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
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

    private final AppUserRepository users;
    private final UserInserter userInserter;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final JdbcTemplate jdbc;


    public AccountSignupService(
            AppUserRepository users,
            UserInserter userInserter,
            PasswordEncoder passwordEncoder,
            RateLimiter rateLimiter,
            JdbcTemplate jdbc) {
        this.users = users;
        this.userInserter = userInserter;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.jdbc = jdbc;
    }

    /**
     * An account's identifiers, normalised, with at least one of them present.
     *
     * <p>Both registration paths need the same three things — trim and lower-case the address,
     * put the number in canonical form, and refuse the case where neither was given — and getting
     * any of them subtly different between the two is how a number registered at the desk stops
     * matching the same number typed at login.
     *
     * @param email  lower-cased, or null when none was given
     * @param mobile canonical {@code +94771234567}, or null when none was given
     */
    public record Identifiers(String email, String mobile) {

        public static Identifiers of(String email, String mobile) {
            String cleanEmail =
                    email == null || email.isBlank()
                            ? null
                            : email.trim().toLowerCase(Locale.ROOT);

            String cleanMobile;
            try {
                cleanMobile = PhoneNumber.normalise(mobile);
            } catch (IllegalArgumentException unreadable) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_MOBILE",
                        "That is not a phone number this can read. Use 0771234567 or +94771234567.");
            }

            if (cleanEmail == null && cleanMobile == null) {
                // The database enforces this too, but a CHECK violation surfaces as a 500 with a
                // constraint name in it. This is the same rule said in the language of the form.
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "IDENTIFIER_REQUIRED",
                        "Enter an email address or a phone number. Either will do; both is fine.");
            }
            return new Identifiers(cleanEmail, cleanMobile);
        }

        /** Only the identifiers that exist, so the audit trail does not record absent fields. */
        Map<String, String> forAudit(String... extra) {
            Map<String, String> entries = new LinkedHashMap<>();
            if (email != null) entries.put("email", email);
            if (mobile != null) entries.put("mobile", mobile);
            for (int i = 0; i + 1 < extra.length; i += 2) {
                entries.put(extra[i], extra[i + 1]);
            }
            return entries;
        }
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

        Identifiers identifiers = Identifiers.of(email, mobile);

        AppUser user = new AppUser();
        user.setEmail(identifiers.email());
        user.setFullName(fullName.trim());
        user.setMobile(identifiers.mobile());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));

        // Active immediately. There is no verification step any more: many customers have no email
        // address at all, so a confirmation link was a door that a large part of the intended
        // membership could never walk through.
        user.setStatusValue(UserStatus.ACTIVE);

        // Inserted in its own transaction. A duplicate must leave this one usable — PostgreSQL
        // aborts a transaction outright on a constraint violation, so catching it inline would
        // kill the audit write that follows. See UserInserter.
        Optional<AppUser> saved = userInserter.insertIfIdentifiersFree(user);

        if (saved.isEmpty()) {
            // Deliberately the same outcome an attacker would see for a fresh identifier: the
            // caller is told the same thing either way, so this endpoint cannot be used to
            // discover who has an account. Only the log records which happened.
            return;
        }

        grantDistributorRole(saved.get().getId());
        AuditContext.record(saved.get().getId(), null, identifiers.forAudit());
    }

    /**
     * What an administrator needs to write down and hand over.
     *
     * <p>Either field may be null — an account is identified by an email address, a phone number,
     * or both — so the caller must show whichever is present rather than assuming the address.
     */
    public record CreatedAccount(UUID id, String email, String mobile) {}

    /**
     * Creates an account for somebody who cannot create one themselves.
     *
     * <p>The client asked for this because most of their customers are not comfortable with a
     * signup form. An administrator takes their details in person and does it for them.
     *
     * <p>Two deliberate differences from {@link #register}:
     *
     * <ul>
     *   <li><b>Not rate limited by IP.</b> The public form's limit exists to stop a stranger
     *       creating accounts in bulk; an administrator registering a dozen people in a morning is
     *       the intended use, and blocking it would be absurd.
     *   <li><b>A duplicate is reported.</b> Public signup is deliberately vague about whether an
     *       identifier is taken, so it cannot be used to discover who has an account. An
     *       administrator is entitled to know, and needs to — otherwise they cannot tell whether
     *       the person in front of them is already registered.
     * </ul>
     *
     * @return the new account's id and the identifiers it was given
     */
    @Transactional
    @Audited(action = "ACCOUNT_REGISTERED_ON_BEHALF", entityType = "app_user", auditFailures = true)
    public CreatedAccount registerOnBehalf(
            String fullName, String email, String mobile, String rawPassword) {

        Identifiers identifiers = Identifiers.of(email, mobile);

        AppUser user = new AppUser();
        user.setEmail(identifiers.email());
        user.setFullName(fullName.trim());
        user.setMobile(identifiers.mobile());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatusValue(UserStatus.ACTIVE);

        Optional<AppUser> saved = userInserter.insertIfIdentifiersFree(user);

        if (saved.isEmpty()) {
            throw takenBy(identifiers);
        }

        grantDistributorRole(saved.get().getId());
        AuditContext.record(
                saved.get().getId(),
                null,
                identifiers.forAudit("createdBy", "administrator"));
        return new CreatedAccount(saved.get().getId(), identifiers.email(), identifiers.mobile());
    }

    /**
     * Which identifier collided.
     *
     * <p>Only reached when the insert already failed, so the extra queries cost nothing on the
     * path that matters. Saying "that email is taken" when it was the phone number sends an
     * administrator looking for the wrong record.
     */
    private ConflictException takenBy(Identifiers identifiers) {
        if (identifiers.email() != null && users.findByEmail(identifiers.email()).isPresent()) {
            ConflictException taken =
                    new ConflictException(
                            "EMAIL_ALREADY_REGISTERED",
                            "Somebody already has an account with that email address.");
            taken.with("email", identifiers.email());
            return taken;
        }
        if (identifiers.mobile() != null && users.findByMobile(identifiers.mobile()).isPresent()) {
            ConflictException taken =
                    new ConflictException(
                            "MOBILE_ALREADY_REGISTERED",
                            "Somebody already has an account with that phone number.");
            taken.with("mobile", identifiers.mobile());
            return taken;
        }
        // Neither is found, so the row that blocked us was written between the insert and these
        // reads. Rare, and the honest answer is that something is already using these details.
        return new ConflictException(
                "ACCOUNT_ALREADY_REGISTERED",
                "An account already exists with those details.");
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


    // ------------------------------------------------------------------ verification


}
