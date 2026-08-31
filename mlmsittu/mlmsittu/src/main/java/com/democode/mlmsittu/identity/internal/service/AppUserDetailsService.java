package com.democode.mlmsittu.identity.internal.service;

import com.democode.mlmsittu.identity.internal.domain.AppRole;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a user by email for Spring Security.
 *
 * <p>{@link AuthService} does not route through this — it needs finer control over the MFA
 * handshake than {@code UserDetailsService} exposes. It exists for two other reasons, both real:
 *
 * <ul>
 *   <li>Without a {@code UserDetailsService} bean, Boot auto-configures an in-memory one and
 *       prints a generated password at every startup. That account is a live credential nobody is
 *       tracking, and it should not exist.
 *   <li>Anything later that does want the standard {@code AuthenticationManager} path — an
 *       {@code AuthenticationProvider}, a remember-me service — needs this to be here.
 * </ul>
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticatedUser loadUserByUsername(String email) throws UsernameNotFoundException {
        return users.findByEmail(email)
                .map(
                        user ->
                                new AuthenticatedUser(
                                        user.getId(),
                                        user.getEmail(),
                                        user.getFullName(),
                                        user.getRoles().stream()
                                                .map(AppRole::getCode)
                                                .collect(Collectors.toCollection(
                                                        LinkedHashSet::new))))
                .orElseThrow(() -> new UsernameNotFoundException("No such user"));
    }
}
