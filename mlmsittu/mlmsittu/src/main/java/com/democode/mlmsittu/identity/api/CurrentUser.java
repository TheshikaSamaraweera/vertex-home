package com.democode.mlmsittu.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Who is making this request.
 *
 * <p>Every stock movement, purchase order and receipt is attributable to a person, so modules that
 * write those need the actor's id — but they must not reach into {@code identity.internal} to get
 * it. This is the contract they depend on instead.
 */
public interface CurrentUser {

    Optional<UUID> id();

    /**
     * @throws com.democode.mlmsittu.shared.error.UnauthenticatedException when nobody is signed in.
     *     Callers on authenticated endpoints can treat this as impossible; it exists so that a
     *     mistake in the security configuration fails loudly instead of writing a null actor.
     */
    UUID requireId();

    Optional<String> email();

    /**
     * Whether the caller holds a role, honouring the role hierarchy — a super admin answers true
     * for {@code ADMIN}.
     *
     * <p>Exists for the cases {@code @PreAuthorize} cannot express: not "may you call this
     * endpoint", but "may you set <em>this field</em>". Retail and wholesale prices are the first
     * of those — everybody may edit an item, only a super admin may change what it is quoted at.
     *
     * @param roleCode the bare code, e.g. {@code SUPER_ADMIN}, with no {@code ROLE_} prefix
     */
    boolean hasRole(String roleCode);
}
