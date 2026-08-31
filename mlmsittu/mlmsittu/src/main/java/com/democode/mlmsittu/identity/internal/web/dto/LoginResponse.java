package com.democode.mlmsittu.identity.internal.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The answer to both login steps.
 *
 * <p>Three shapes come out of this one record:
 *
 * <ul>
 *   <li>{@code mfaRequired=false} plus {@code user} — logged in, session cookie set.
 *   <li>{@code mfaRequired=true} plus {@code challengeId} — password accepted, send the code.
 *   <li>{@code enrolmentRequired=true} plus {@code totpSecret} and {@code otpauthUri} — an
 *       administrator with no authenticator set up yet. The secret appears exactly once, here.
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        boolean mfaRequired,
        boolean enrolmentRequired,
        String challengeId,
        String totpSecret,
        String otpauthUri,
        UserSummary user) {

    public static LoginResponse authenticated(UserSummary user) {
        return new LoginResponse(false, false, null, null, null, user);
    }

    public static LoginResponse challenge(String challengeId) {
        return new LoginResponse(true, false, challengeId, null, null, null);
    }

    public static LoginResponse enrolment(String challengeId, String secret, String otpauthUri) {
        return new LoginResponse(true, true, challengeId, secret, otpauthUri, null);
    }
}
