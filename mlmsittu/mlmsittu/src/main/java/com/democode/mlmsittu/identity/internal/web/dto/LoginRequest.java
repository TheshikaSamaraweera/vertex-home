package com.democode.mlmsittu.identity.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One field for either kind of identifier.
 *
 * <p>An account is identified by an email address, a phone number, or both, so the sign-in form
 * asks for whichever the person has. {@code AuthService} decides which it was given and looks it up
 * accordingly.
 *
 * <p><b>No {@code @Email}.</b> It used to carry one, and it cannot now — the annotation would
 * reject every phone number before the request reached anything that could look it up. There is no
 * format check here at all, deliberately: an identifier that is neither a valid address nor a
 * readable number is simply one that matches no account, and answering "that is not an email
 * address" would tell somebody probing the form which spellings are worth trying.
 *
 * <p>320 is the maximum length of an email address, and a phone number is far shorter, so the one
 * bound covers both.
 */
public record LoginRequest(
        @NotBlank(message = "REQUIRED") @Size(max = 320) String identifier,
        @NotBlank(message = "REQUIRED") @Size(max = 200) String password) {}
