package com.democode.mlmsittu.identity.internal.service;

import com.democode.mlmsittu.identity.internal.domain.AppRole;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.repo.AppRoleRepository;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.identity.internal.web.dto.UserSummary;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.ForbiddenException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administration of user accounts. Every mutating method here is audited (architecture §8.1). */
@Service
public class UserAdminService {

    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final AppUserRepository users;
    private final AppRoleRepository roles;

    public UserAdminService(AppUserRepository users, AppRoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    /**
     * Staff accounts only — everyone who holds a role other than {@code DISTRIBUTOR}.
     *
     * <p>This screen exists to answer "who may approve KYC, verify payments and change roles", and
     * every customer in the list is noise against that question. It is also the wrong place to
     * look at customers: they are people with registrations, referrers, packs and stages, and none
     * of that is here. Onboarding → Customers is the screen that shows them properly.
     *
     * <p>It stops being cosmetic as the network grows. Ten thousand customers and eight staff in
     * one list is a page where the dangerous rows are impossible to find, and the roles that
     * matter are the ones nobody can see.
     *
     * <p>A customer is not hidden from administration — {@link #getUser} still returns anyone by
     * id, and role changes still work on any account. Only the listing is narrowed, so promoting a
     * customer to staff means going through their customer record, which is the deliberate order:
     * you should be looking at who they are when you decide that.
     */
    @Transactional(readOnly = true)
    public List<UserSummary> listUsers() {
        return users.findAllOrdered().stream()
                .map(UserSummary::from)
                .filter(UserAdminService::isStaff)
                .toList();
    }

    /**
     * Anything other than {@code DISTRIBUTOR} makes an account staff.
     *
     * <p>Written as "not only a customer" rather than as a list of staff roles on purpose: a role
     * added later is staff by default and appears here without anybody remembering to add it. The
     * reverse — naming the staff roles — fails silently the first time somebody adds one, and it
     * fails by hiding an account that can approve things.
     *
     * <p>An account with no roles at all is included. It is not a customer, it is a loose end, and
     * the screen that manages roles is exactly where it should be visible.
     */
    private static boolean isStaff(UserSummary user) {
        return user.roles().stream().anyMatch(role -> !"DISTRIBUTOR".equals(role));
    }

    @Transactional(readOnly = true)
    public UserSummary getUser(UUID id) {
        return users.findById(id).map(UserSummary::from).orElseThrow(() -> userNotFound(id));
    }

    /**
     * Replaces a user's roles wholesale.
     *
     * <p>This is the operation Gate 1 uses to prove the audit trail works, and it is also the
     * single most dangerous endpoint in Phase 1 — it decides who may approve KYC and verify
     * payments. Hence the two guards below.
     */
    @Transactional
    @Audited(action = "USER_ROLES_CHANGED", entityType = "app_user", auditFailures = true)
    public UserSummary replaceRoles(UUID userId, Set<String> requestedCodes, UUID actorId) {

        AppUser user = users.findById(userId).orElseThrow(() -> userNotFound(userId));
        AuditContext.record(userId, null, null);

        Set<String> normalised = new LinkedHashSet<>();
        requestedCodes.forEach(code -> normalised.add(code.trim().toUpperCase()));

        List<AppRole> resolved = roles.findByCodeIn(normalised);
        if (resolved.size() != normalised.size()) {
            Set<String> known = new TreeSet<>();
            resolved.forEach(role -> known.add(role.getCode()));
            Set<String> unknown = new TreeSet<>(normalised);
            unknown.removeAll(known);
            throw new ApiException(
                            HttpStatus.BAD_REQUEST, "UNKNOWN_ROLE", "One or more roles do not exist.")
                    .with("unknownRoles", unknown);
        }

        Set<String> before = new TreeSet<>();
        user.getRoles().forEach(role -> before.add(role.getCode()));

        // Guard 1: no self-demotion. An administrator who accidentally drops their own
        // SUPER_ADMIN cannot undo it, because undoing it requires SUPER_ADMIN.
        if (userId.equals(actorId) && before.contains(SUPER_ADMIN) && !normalised.contains(SUPER_ADMIN)) {
            throw new ForbiddenException(
                    "CANNOT_REMOVE_OWN_SUPER_ADMIN",
                    "You cannot remove your own super_admin role. Ask another super admin.");
        }

        // Guard 2: never leave the system with nobody who can grant roles.
        if (before.contains(SUPER_ADMIN) && !normalised.contains(SUPER_ADMIN)
                && countSuperAdmins() <= 1) {
            throw new ConflictException(
                    "LAST_SUPER_ADMIN",
                    "This is the only super admin. Promote someone else first.");
        }

        user.getRoles().clear();
        user.getRoles().addAll(resolved);
        users.save(user);

        Set<String> after = new TreeSet<>(normalised);
        AuditContext.record(userId, Map.of("roles", before), Map.of("roles", after));

        return UserSummary.from(user);
    }

    private long countSuperAdmins() {
        return users.findAllOrdered().stream()
                .filter(
                        candidate ->
                                candidate.getRoles().stream()
                                        .anyMatch(role -> SUPER_ADMIN.equals(role.getCode())))
                .count();
    }

    private NotFoundException userNotFound(UUID id) {
        return new NotFoundException("USER_NOT_FOUND", "No user with that id.");
    }
}
