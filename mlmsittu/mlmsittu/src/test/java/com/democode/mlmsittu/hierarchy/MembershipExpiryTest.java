package com.democode.mlmsittu.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.hierarchy.internal.DistributorService;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.portal.api.PortalAccess;
import com.democode.mlmsittu.portal.internal.PortalService;
import com.democode.mlmsittu.shared.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Membership expiry")
class MembershipExpiryTest {

    @Autowired private DistributorService distributors;
    @Autowired private PortalService portal;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("approval sets an expiry, counted from that moment")
    void approvalSetsAnExpiry() {
        Approved approved = approveSomebody();

        Instant expiresAt =
                jdbc.queryForObject(
                        "SELECT expires_at FROM distributor WHERE id = ?",
                        Instant.class,
                        approved.distributorId());

        assertThat(expiresAt).isNotNull();
        assertThat(Duration.between(Instant.now(), expiresAt).toDays())
                .as("the configured period, which V29 sets to 60 days")
                .isBetween(58L, 61L);
    }

    @Test
    @DisplayName("an expired membership closes the portal and nothing else")
    void expiryClosesThePortal() {
        Approved approved = approveSomebody();

        assertThat(portal.viewFor(approved.userId()).access()).isEqualTo(PortalAccess.ACTIVE);

        expire(approved.distributorId());

        assertThat(portal.viewFor(approved.userId()).access())
                .as("distinct from REJECTED: they were admitted, their time is up")
                .isEqualTo(PortalAccess.EXPIRED);

        // The tree is untouched. A lapsed membership is between this person and the business, and
        // must not reach into their referrer's stage count or anybody's entitlement.
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM distributor WHERE id = ?",
                        String.class,
                        approved.distributorId()))
                .isEqualTo("active");
        assertThat(jdbc.queryForObject(
                        "SELECT business_id FROM distributor WHERE id = ?",
                        String.class,
                        approved.distributorId()))
                .isNotNull();
    }

    @Test
    @DisplayName("extending an expired membership counts from today, not from the old date")
    void extendingStartsFromToday() {
        Approved approved = approveSomebody();

        // Thirty days overdue.
        jdbc.update(
                "UPDATE distributor SET expires_at = now() - INTERVAL '30 days' WHERE id = ?",
                approved.distributorId());

        Instant extended = distributors.extendMembership(approved.distributorId(), 60);

        // Sixty days from now, not thirty. Nobody is charged for time they could not use, and the
        // arithmetic is the one a person at a desk would do out loud.
        assertThat(Duration.between(Instant.now(), extended).toDays()).isBetween(58L, 61L);
        assertThat(portal.viewFor(approved.userId()).access()).isEqualTo(PortalAccess.ACTIVE);
    }

    @Test
    @DisplayName("extending a live membership adds to the date it already has")
    void extendingALiveMembershipAdds() {
        Approved approved = approveSomebody();

        Instant extended = distributors.extendMembership(approved.distributorId(), 30);

        // Roughly 60 already there plus 30, not 30 from today — somebody renewing early keeps the
        // time they have already paid for.
        assertThat(Duration.between(Instant.now(), extended).toDays()).isBetween(88L, 91L);
    }

    @Test
    @DisplayName("a nonsense extension is refused")
    void anAbsurdExtensionIsRefused() {
        Approved approved = approveSomebody();
        assertThatThrownBy(() -> distributors.extendMembership(approved.distributorId(), 0))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> distributors.extendMembership(approved.distributorId(), 99_999))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("changing the period does not move anybody's existing date")
    void changingThePeriodLeavesExistingDatesAlone() {
        Approved approved = approveSomebody();
        Instant before =
                jdbc.queryForObject(
                        "SELECT expires_at FROM distributor WHERE id = ?",
                        Instant.class,
                        approved.distributorId());

        distributors.setMembershipPeriodDays(90, approved.userId());

        Instant after =
                jdbc.queryForObject(
                        "SELECT expires_at FROM distributor WHERE id = ?",
                        Instant.class,
                        approved.distributorId());

        // A date somebody has been told, and may have paid against, is not something a settings
        // change should silently rewrite.
        assertThat(after).isEqualTo(before);

        // Restore, so the other tests in this class see the migration's value.
        distributors.setMembershipPeriodDays(60, approved.userId());
    }

    // ------------------------------------------------------------------ fixtures

    private record Approved(UUID userId, UUID distributorId) {}

    private Approved approveSomebody() {
        UUID userId = newUser();
        UUID distributorId = distributors.createPending(userId, null);
        distributors.attachToReferrer(distributorId, null);
        return new Approved(userId, distributorId);
    }

    private void expire(UUID distributorId) {
        jdbc.update(
                "UPDATE distributor SET expires_at = now() - INTERVAL '1 day' WHERE id = ?",
                distributorId);
    }

    private UUID newUser() {
        AppUser user = new AppUser();
        user.setEmail("expiry-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Expiry fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }
}
