package com.democode.mlmsittu.identity.internal.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Holds the short-lived state between "password accepted" and "TOTP accepted".
 *
 * <p>A challenge is not a session. It grants nothing on its own — it only remembers which user
 * passed step one, so step two does not need the password again. Sitting off-session also means a
 * half-authenticated caller never holds a cookie that any {@code @PreAuthorize} would honour.
 *
 * <p>In memory for Phase 1, alongside sessions. Both move to Redis in Phase 7.
 */
@Component
public class MfaChallengeStore {

    private static final Duration TTL = Duration.ofMinutes(5);

    /** Architecture §3.2: five attempts on the admin 2FA channel. */
    private static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    public static final class Challenge {
        private final UUID userId;
        private final String pendingSecret;
        private final Instant expiresAt;
        private int attempts;

        private Challenge(UUID userId, String pendingSecret, Instant expiresAt) {
            this.userId = userId;
            this.pendingSecret = pendingSecret;
            this.expiresAt = expiresAt;
        }

        public UUID userId() {
            return userId;
        }

        /**
         * Set only during first-time enrolment. The secret is deliberately <em>not</em> persisted
         * until a correct code proves the user actually stored it — otherwise a user who closes
         * the tab mid-enrolment is locked out of an account whose secret nobody holds.
         */
        public String pendingSecret() {
            return pendingSecret;
        }

        public boolean isEnrolment() {
            return pendingSecret != null;
        }
    }

    /** @param pendingSecret the secret being enrolled, or null when the user already has one. */
    public String create(UUID userId, String pendingSecret) {
        purgeExpired();
        byte[] token = new byte[24];
        RANDOM.nextBytes(token);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        challenges.put(id, new Challenge(userId, pendingSecret, Instant.now().plus(TTL)));
        return id;
    }

    /**
     * Looks up a challenge and counts the attempt. Returns empty when the challenge is unknown,
     * expired, or has burned through its attempt budget — the caller cannot tell which, and should
     * not be able to.
     */
    public Optional<Challenge> attempt(String challengeId) {
        if (challengeId == null) {
            return Optional.empty();
        }
        Challenge challenge = challenges.get(challengeId);
        if (challenge == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(challenge.expiresAt)) {
            challenges.remove(challengeId);
            return Optional.empty();
        }
        if (++challenge.attempts > MAX_ATTEMPTS) {
            challenges.remove(challengeId);
            return Optional.empty();
        }
        return Optional.of(challenge);
    }

    /** Called once the code is accepted — a challenge is single-use. */
    public void discard(String challengeId) {
        challenges.remove(challengeId);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        challenges.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt));
    }
}
