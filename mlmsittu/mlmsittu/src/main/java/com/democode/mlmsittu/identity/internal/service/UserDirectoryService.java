package com.democode.mlmsittu.identity.internal.service;

import com.democode.mlmsittu.identity.api.UserDirectory;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserDirectoryService implements UserDirectory {

    private final AppUserRepository users;

    public UserDirectoryService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserRef> findById(UUID userId) {
        return users.findById(userId).map(this::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserRef> findByEmail(String email) {
        return users.findByEmail(email).map(this::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRef> findByRole(String roleCode) {
        // Active accounts only. Telling a suspended administrator that a pack is waiting is noise
        // they cannot act on.
        return users.findActiveByRoleCode(roleCode).stream().map(this::toRef).toList();
    }

    private UserRef toRef(AppUser user) {
        return new UserRef(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getMobile(),
                user.getStatus(),
                user.isEmailVerified(),
                user.getCreatedAt());
    }
}
