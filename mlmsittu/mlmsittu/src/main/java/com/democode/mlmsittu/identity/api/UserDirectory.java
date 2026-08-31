package com.democode.mlmsittu.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lookup of users by other modules — to resolve who recorded a movement, or to attribute seeded
 * data to a real account. Read-only on purpose: nothing outside {@code identity} creates or
 * modifies users.
 */
public interface UserDirectory {

    /**
     * @param joinedAt when the account was created — the "joining date" an admin profile shows,
     *     which is distinct from the date a business registration was submitted or approved
     */
    record UserRef(
            UUID id,
            String email,
            String fullName,
            String mobile,
            String status,
            boolean emailVerified,
            Instant joinedAt) {}

    Optional<UserRef> findById(UUID userId);

    Optional<UserRef> findByEmail(String email);

    /**
     * Everyone holding a role, for notifying a group rather than a person.
     *
     * <p>Direct holders only — the role hierarchy is not applied. A super admin who has not been
     * given {@code ADMIN} explicitly will not appear under it, which is the honest reading of
     * "who holds this role"; callers that want both ask for both.
     *
     * @param roleCode the bare code, e.g. {@code ADMIN}, with no {@code ROLE_} prefix
     */
    List<UserRef> findByRole(String roleCode);
}
