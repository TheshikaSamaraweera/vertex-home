package com.democode.mlmsittu.identity.internal.seed;

import com.democode.mlmsittu.identity.internal.domain.AppRole;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppRoleRepository;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Development seed data — P0-07, extended each phase.
 *
 * <p>Runs only under the {@code seed} profile:
 *
 * <pre>
 *   ./gradlew bootRun --args="--spring.profiles.active=seed"
 * </pre>
 *
 * <p>Idempotent: existing accounts are left untouched, so it is safe to run every day as the plan
 * suggests. Phase 2 adds items, categories, locations and the two deliberately-overlapping item
 * sets here.
 *
 * <p><b>Never enable this profile outside a developer machine.</b> Every account below shares one
 * published password and one published TOTP secret.
 */
@Component
@Profile("seed")
@Order(10)
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String PASSWORD = "Password123!";

    /**
     * One secret across all six accounts so a tester adds a single entry to their authenticator
     * app and can then sign in as any role. Real accounts get a unique secret at enrolment.
     */
    private static final String SHARED_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private record SeedUser(String email, String fullName, String roleCode, boolean totpEnrolled) {}

    private static final List<SeedUser> SEED_USERS =
            List.of(
                    new SeedUser("super@mlmsittu.local", "Sithara Super Admin", "SUPER_ADMIN", true),
                    // Everything a super admin can do except manage users — the distinction the
                    // client asked for, and the only way to test it is to have one.
                    new SeedUser("admin@mlmsittu.local", "Anushka Admin", "ADMIN", true),
                    new SeedUser("kyc@mlmsittu.local", "Kavindu Reviewer", "KYC_REVIEWER", true),
                    new SeedUser(
                            "inventory@mlmsittu.local", "Ishara Clerk", "INVENTORY_CLERK", true),
                    new SeedUser(
                            "procurement@mlmsittu.local",
                            "Pasan Procurement",
                            "PROCUREMENT_OFFICER",
                            true),
                    new SeedUser(
                            "finance@mlmsittu.local", "Fathima Finance", "FINANCE_OFFICER", true),
                    // A second finance officer, so P5-07 can be tested as it is written: officer A
                    // records the payment and is refused, officer B verifies it. With only one
                    // account in the role there is nobody for the four-eyes rule to hand over to.
                    new SeedUser(
                            "finance2@mlmsittu.local", "Farhan Finance", "FINANCE_OFFICER", true),
                    new SeedUser("support@mlmsittu.local", "Sanduni Support", "SUPPORT_AGENT", true),
                    // Deliberately un-enrolled: exercises the first-login TOTP enrolment path, and
                    // gives a second super admin so the "last super admin" guard can be tested.
                    new SeedUser(
                            "newadmin@mlmsittu.local", "Nimal New Admin", "SUPER_ADMIN", false));

    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final PasswordEncoder passwordEncoder;

    public DevDataSeeder(
            AppUserRepository users, AppRoleRepository roles, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0;

        for (SeedUser seed : SEED_USERS) {
            if (users.findByEmail(seed.email()).isPresent()) {
                continue;
            }

            AppRole role =
                    roles.findByCode(seed.roleCode())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Role "
                                                            + seed.roleCode()
                                                            + " is missing. Has V2__identity.sql"
                                                            + " run?"));

            AppUser user = new AppUser();
            user.setEmail(seed.email());
            user.setFullName(seed.fullName());
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            user.setStatusValue(UserStatus.ACTIVE);
            user.setEmailVerified(true);
            user.setMobileVerified(true);
            user.setMobile("+94770000000");
            user.setRoles(Set.of(role));

            if (seed.totpEnrolled()) {
                user.setTotpSecret(SHARED_TOTP_SECRET);
                user.setTotpEnabled(true);
            }

            users.save(user);
            created++;
        }

        report(created);
    }

    private void report(int created) {
        StringBuilder banner = new StringBuilder();
        banner.append("\n");
        banner.append("=".repeat(78)).append("\n");
        banner.append("  DEV SEED DATA — never run the 'seed' profile outside a laptop\n");
        banner.append("=".repeat(78)).append("\n");
        banner.append("  accounts created this run : ").append(created).append("\n");
        banner.append("  password (all accounts)   : ").append(PASSWORD).append("\n");
        banner.append("  TOTP secret (all but one) : ").append(SHARED_TOTP_SECRET).append("\n");
        banner.append("\n");
        banner.append("  Get a code without a phone:\n");
        banner.append("      ./gradlew totp -Psecret=").append(SHARED_TOTP_SECRET).append("\n");
        banner.append("\n");
        SEED_USERS.forEach(
                seed ->
                        banner.append(String.format("  %-30s %-22s %s%n",
                                seed.email(),
                                seed.roleCode(),
                                seed.totpEnrolled() ? "TOTP ready" : "TOTP not enrolled")));
        banner.append("=".repeat(78));

        log.info(banner.toString());
    }
}
