package com.democode.mlmsittu.identity.internal.service;

import com.democode.mlmsittu.identity.api.PasswordConfirmation;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.shared.error.ForbiddenException;
import com.democode.mlmsittu.shared.error.RateLimitExceededException;
import com.democode.mlmsittu.shared.ratelimit.api.RateLimiter;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordConfirmationService implements PasswordConfirmation {

    /** Five tries in fifteen minutes: plenty for a typo, useless for guessing. */
    private static final int MAX_ATTEMPTS = 5;

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;

    public PasswordConfirmationService(
            AppUserRepository users, PasswordEncoder passwordEncoder, RateLimiter rateLimiter) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void confirm(UUID userId, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new ForbiddenException(
                    "PASSWORD_REQUIRED", "Enter your password to confirm this change.");
        }
        if (!rateLimiter.tryAcquire("confirm-password:" + userId, MAX_ATTEMPTS, WINDOW)) {
            throw new RateLimitExceededException(
                    "PASSWORD_CONFIRM_RATE_LIMITED",
                    "Too many password attempts. Try again in " + WINDOW.toMinutes() + " minutes.");
        }
        String hash = users.findById(userId).map(AppUser::getPasswordHash).orElse(null);
        if (hash == null || !passwordEncoder.matches(rawPassword, hash)) {
            throw new ForbiddenException("PASSWORD_INCORRECT", "That password is not correct.");
        }
    }
}
