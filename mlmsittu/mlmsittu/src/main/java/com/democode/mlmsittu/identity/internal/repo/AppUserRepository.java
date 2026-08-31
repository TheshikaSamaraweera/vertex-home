package com.democode.mlmsittu.identity.internal.repo;

import com.democode.mlmsittu.identity.internal.domain.AppUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Matched on {@code lower(email)} to line up with {@code idx_app_user_email}. Spring Data's
     * derived {@code IgnoreCase} keyword emits {@code upper(...)} instead, which would not use
     * that index.
     */
    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    Optional<AppUser> findByEmail(@Param("email") String email);

    @Query("select u from AppUser u order by u.createdAt asc, u.id asc")
    List<AppUser> findAllOrdered();

    /**
     * Everyone holding a role, by its code.
     *
     * <p>Native, because the join runs through {@code user_role} and {@code app_role} and the
     * entity graph does not model either — roles are loaded as codes when a session is built, not
     * as an association to walk.
     */
    @Query(
            value =
                    """
                    SELECT u.* FROM app_user u
                    JOIN user_role ur ON ur.user_id = u.id
                    JOIN app_role r ON r.id = ur.role_id
                    WHERE r.code = :roleCode AND u.status = 'active'
                    ORDER BY u.full_name
                    """,
            nativeQuery = true)
    List<AppUser> findActiveByRoleCode(@Param("roleCode") String roleCode);
}
