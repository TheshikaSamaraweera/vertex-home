package com.democode.mlmsittu.identity.internal.service;

import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.shared.error.UnauthenticatedException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService implements CurrentUser {

    private final RoleHierarchy roleHierarchy;

    public CurrentUserService(RoleHierarchy roleHierarchy) {
        this.roleHierarchy = roleHierarchy;
    }

    @Override
    public Optional<UUID> id() {
        return principal().map(AuthenticatedUser::id);
    }

    @Override
    public UUID requireId() {
        return id().orElseThrow(
                        () ->
                                new UnauthenticatedException(
                                        "UNAUTHENTICATED", "Authentication is required."));
    }

    @Override
    public Optional<String> email() {
        return principal().map(AuthenticatedUser::email);
    }

    /**
     * Asks the same hierarchy {@code @PreAuthorize} uses, rather than reading the granted
     * authorities directly. Reading them raw would answer false for a super admin asked about
     * {@code ADMIN} — they hold one authority, not both — and the two checks would then disagree
     * about the same person.
     */
    @Override
    public boolean hasRole(String roleCode) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        String wanted = "ROLE_" + roleCode;
        return roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                .anyMatch(authority -> wanted.equals(authority.getAuthority()));
    }

    private Optional<AuthenticatedUser> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }
}
