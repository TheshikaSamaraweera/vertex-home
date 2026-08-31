package com.democode.mlmsittu.identity.internal.repo;

import com.democode.mlmsittu.identity.internal.domain.AppRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppRoleRepository extends JpaRepository<AppRole, UUID> {

    Optional<AppRole> findByCode(String code);

    List<AppRole> findByCodeIn(Collection<String> codes);
}
