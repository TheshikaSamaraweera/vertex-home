package com.democode.mlmsittu.hierarchy.internal.seed;

import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A small referral tree, so the system is testable from a fresh database.
 *
 * <p>Without this there is a hole in the very first thing a new distributor does: the registration
 * form requires a referrer's Business ID, IDs are only allocated at approval, and on an empty
 * database nobody has one. The first distributor could only be created by hand in SQL — which is
 * not a thing anyone should have to do to try the product.
 *
 * <p>Seeds a root with two referrals, which leaves the root at 2 of 4 places used: enough to
 * register against, enough to see the tree expand, and enough headroom to fill the last two
 * places and watch the width cap refuse a fifth.
 *
 * <p>Users are inserted with raw SQL rather than through the identity module — this is dev-only
 * fixture code and referencing another module's internals for it would earn a boundary failure it
 * does not deserve.
 */
@Component
@Profile("seed")
@Order(50)
public class HierarchyDevSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HierarchyDevSeeder.class);

    private record SeedDistributor(String email, String fullName) {}

    private static final SeedDistributor ROOT =
            new SeedDistributor("root.distributor@mlmsittu.local", "Ravi Root Distributor");

    private static final List<SeedDistributor> CHILDREN =
            List.of(
                    new SeedDistributor("dist.first@mlmsittu.local", "Dilani First Referral"),
                    new SeedDistributor("dist.second@mlmsittu.local", "Suresh Second Referral"));

    private final ReferralHierarchy hierarchy;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public HierarchyDevSeeder(
            ReferralHierarchy hierarchy, JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.hierarchy = hierarchy;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!hierarchy.roots().isEmpty()) {
            log.info("Seed: hierarchy — already populated, leaving it alone");
            return;
        }

        UUID rootDistributor = hierarchy.createPending(userFor(ROOT), null);
        String rootBusinessId = hierarchy.attachToReferrer(rootDistributor, null);

        for (SeedDistributor child : CHILDREN) {
            UUID childDistributor = hierarchy.createPending(userFor(child), rootDistributor);
            hierarchy.attachToReferrer(childDistributor, rootDistributor);
        }

        log.info(
                """

                ==============================================================================
                  SEED: referral tree
                ==============================================================================
                  Root referrer Business ID : {}
                  Referral places used      : {} of 4
                  Sign in as                : {} / Password123!

                  Use that Business ID as the referrer when testing registration.
                ==============================================================================""",
                rootBusinessId,
                CHILDREN.size(),
                ROOT.email());
    }

    /** Creates the account if it is not already there, and returns its id either way. */
    private UUID userFor(SeedDistributor seed) {
        Optional<UUID> existing =
                jdbc
                        .query(
                                "SELECT id FROM app_user WHERE lower(email) = ?",
                                (rs, rowNum) -> rs.getObject(1, UUID.class),
                                seed.email())
                        .stream()
                        .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        // Verified and active: these stand in for people who completed signup long ago, so the
        // hierarchy is usable without walking each of them through email confirmation.
        return jdbc.queryForObject(
                """
                INSERT INTO app_user
                    (email, full_name, password_hash, status, email_verified, mobile_verified)
                VALUES (?, ?, ?, 'active', true, true)
                RETURNING id
                """,
                UUID.class,
                seed.email(),
                seed.fullName(),
                passwordEncoder.encode("Password123!"));
    }
}
