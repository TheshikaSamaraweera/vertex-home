package com.democode.mlmsittu.identity.api;

import java.util.UUID;

/**
 * Asks a signed-in user to prove it is still them before something sensitive.
 *
 * <p>A session proves somebody signed in; it does not prove they are the one at the keyboard now.
 * For a change that overrides what a customer chose, the person making it types their password
 * again.
 */
public interface PasswordConfirmation {

    /**
     * Returns quietly when {@code rawPassword} is this user's password.
     *
     * @throws com.democode.mlmsittu.shared.error.ForbiddenException {@code PASSWORD_INCORRECT}
     *     when it is not, or {@code PASSWORD_REQUIRED} when none was given
     * @throws com.democode.mlmsittu.shared.error.RateLimitExceededException after too many wrong
     *     tries, so this cannot become a way to guess a colleague's password from their open
     *     session
     */
    void confirm(UUID userId, String rawPassword);
}
