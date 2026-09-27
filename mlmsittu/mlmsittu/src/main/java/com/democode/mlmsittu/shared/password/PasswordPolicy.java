package com.democode.mlmsittu.shared.password;

import com.democode.mlmsittu.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * What counts as an acceptable password.
 *
 * <p>Eight to twelve characters, with at least one letter and at least one digit. Set by the
 * client.
 *
 * <h2>The maximum is the unusual part</h2>
 *
 * <p>A minimum length raises the cost of guessing; a maximum lowers it, and this one rules out
 * every passphrase — {@code correct horse battery staple} is refused while {@code Passw0rd} is
 * accepted, though the first is vastly harder to guess. Argon2id hashes any length to the same
 * 32 bytes, so there is no storage or performance reason for a ceiling.
 *
 * <p>It is implemented as asked because it is the client's decision to make, and the rest of the
 * design carries the weight: logins are rate limited by both account and IP, passwords are hashed
 * with Argon2id rather than anything faster, and no password is ever the only thing between
 * somebody and an administrative action.
 *
 * <p>Raising the ceiling later costs nothing and breaks nobody — existing hashes are unaffected by
 * a policy change, because the policy is only consulted when a password is set.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 12;

    /** Said once, so the form, the error and the documentation cannot drift apart. */
    public static final String RULE =
            "Between " + MIN_LENGTH + " and " + MAX_LENGTH
                    + " characters, with at least one letter and one number.";

    private PasswordPolicy() {}

    /** True when {@code candidate} satisfies every rule. */
    public static boolean isAcceptable(String candidate) {
        if (candidate == null
                || candidate.length() < MIN_LENGTH
                || candidate.length() > MAX_LENGTH) {
            return false;
        }
        boolean hasLetter = candidate.chars().anyMatch(Character::isLetter);
        boolean hasDigit = candidate.chars().anyMatch(Character::isDigit);
        return hasLetter && hasDigit;
    }

    /**
     * Throws unless {@code candidate} is acceptable.
     *
     * <p>Checked here rather than with a bean-validation annotation on each request record. There
     * are five places a password is set — signup, admin registration, admin user creation, a reset
     * link, a forced change — and an annotation on four of them plus one that was missed is
     * exactly how a policy ends up not applying to the path somebody actually uses.
     */
    public static void require(String candidate) {
        if (!isAcceptable(candidate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_WEAK", RULE);
        }
    }
}
