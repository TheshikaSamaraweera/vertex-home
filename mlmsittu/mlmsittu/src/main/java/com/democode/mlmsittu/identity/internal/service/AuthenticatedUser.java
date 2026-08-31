package com.democode.mlmsittu.identity.internal.service;

import com.democode.mlmsittu.shared.audit.api.AuditActor;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The principal stored in the session once a login is fully complete.
 *
 * <p>Serializable because Phase 7 moves sessions into Redis (P7-01); getting that wrong now would
 * surface as an obscure serialisation failure weeks later. It carries no password — by the time
 * this object exists the credential check is already done, and keeping the hash in the session
 * would put it in Redis for no reason.
 */
public record AuthenticatedUser(UUID id, String email, String fullName, Set<String> roleCodes)
        implements UserDetails, AuditActor, Serializable {

    public AuthenticatedUser {
        roleCodes = new LinkedHashSet<>(roleCodes);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Spring Security's hasRole('X') looks for the authority ROLE_X.
        return roleCodes.stream()
                .map(code -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + code))
                .toList();
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public UUID auditActorId() {
        return id;
    }

    public List<String> sortedRoleCodes() {
        return roleCodes.stream().sorted().toList();
    }
}
