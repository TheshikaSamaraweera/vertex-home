package com.democode.mlmsittu.identity.internal.web.dto;

import com.democode.mlmsittu.identity.internal.domain.AppRole;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the API is willing to say about a user.
 *
 * <p>Built by hand rather than serialising the entity, so a column added later — a password hash,
 * a TOTP secret, an encrypted NIC — cannot leak into a response by default.
 */
public record UserSummary(
        UUID id,
        String email,
        String fullName,
        String mobile,
        String status,
        boolean emailVerified,
        boolean mobileVerified,
        boolean totpEnabled,
        /**
         * True while the account is on a password an administrator chose.
         *
         * <p>Sign-in succeeds and the client sends them straight to the change-password screen.
         * Enforced here rather than by refusing the login, because somebody who cannot get in at
         * all cannot change anything either.
         */
        boolean mustChangePassword,
        /** The profile picture, or null. Fetched from /api/v1/users/photo/{id}. */
        java.util.UUID profilePhotoId,
        List<String> roles,
        Instant createdAt) {

    public static UserSummary from(AppUser user) {
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getMobile(),
                user.getStatus(),
                user.isEmailVerified(),
                user.isMobileVerified(),
                user.isTotpEnabled(),
                user.isMustChangePassword(),
                user.getProfilePhotoId(),
                user.getRoles().stream().map(AppRole::getCode).sorted().toList(),
                user.getCreatedAt());
    }
}
