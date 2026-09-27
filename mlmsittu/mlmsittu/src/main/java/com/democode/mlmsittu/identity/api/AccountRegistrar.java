package com.democode.mlmsittu.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Creating an account from another module, for a front door that is not the distributor one.
 *
 * <p>Self-signup used to mean one thing, so {@code AccountSignupService} granted {@code
 * DISTRIBUTOR} and there was nothing to decide. Marketing officers sign themselves up too, and
 * their application lives in {@code hierarchy} — which must not write to {@code app_user} itself.
 *
 * <h2>Why a door rather than a copy</h2>
 *
 * <p>Everything that makes account creation correct is behind here: the password policy, the
 * identifier canonicalisation, the insert that decides a duplicate without a read-then-write race,
 * the rate limit on the public form, the verification message. A second module writing its own
 * INSERT would get four of those five right and nobody would notice which one was missing until an
 * officer existed with a three-character password.
 *
 * <p>So {@code identity} still owns every account. This interface only lets another module ask for
 * one, and say which front door the person came in by.
 *
 * <p>The direction matters: {@code hierarchy} already depends on {@code identity} for {@link
 * UserDirectory}. Exposing the officer application the other way instead — {@code identity}
 * reaching into {@code hierarchy} at signup — would have made the two modules mutually dependent,
 * and the shared kernel's own rule exists to stop exactly that.
 */
public interface AccountRegistrar {

    /**
     * Creates an account for somebody filling in a public form, and grants it one role.
     *
     * <p>Rate limited by IP like any public signup, and as vague about duplicates: the caller is
     * told an account was created either way, because a form that says "already taken" is a way to
     * ask who has an account here.
     *
     * <h2>Empty means taken, and the caller must not learn whose</h2>
     *
     * <p>An empty result is returned when the email or mobile already belongs to somebody. The
     * caller's job is then to do nothing further and answer the form exactly as it would have on
     * success.
     *
     * <p>It must not be the existing account's id. A caller that attached its own record to that id
     * would let a stranger who knows somebody's email address bolt an application onto that
     * person's account — and an administrator would then see a real customer's name in a queue and
     * approve it. The vagueness belongs at the form; the caller is told nothing it could misuse.
     *
     * @param roleCode the bare role code to grant, e.g. {@code MARKETING_OFFICER}
     * @return the new account's id, or empty when the identifiers were already taken
     */
    Optional<UUID> registerSelfService(
            String fullName, String email, String mobile, String rawPassword, String roleCode,
            String clientIp);

    /**
     * Creates an account on somebody's behalf, and grants it one role.
     *
     * <p>Not rate limited, and a duplicate is reported rather than hidden — an administrator taking
     * details in person is entitled to know the person in front of them already has an account.
     *
     * @param roleCode the bare role code to grant
     * @return the new account's id
     */
    UUID registerForRole(
            String fullName, String email, String mobile, String rawPassword, String roleCode);
}
