package com.democode.mlmsittu.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.hierarchy.internal.MarketingOfficerService;
import com.democode.mlmsittu.identity.api.AccountRegistrar;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.shared.error.ApiException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Applying to be a marketing officer, and being let in or turned away.
 *
 * <p>The thing worth proving here is the gate. An officer who exists is not an officer who has been
 * approved, and every path that lets somebody work — being assigned a customer above all — has to
 * ask the second question rather than the first.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Marketing officer approval")
class MarketingOfficerApprovalTest {

    @Autowired private MarketingOfficerService officers;
    @Autowired private AccountRegistrar accounts;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("somebody who applies is waiting, holds the role, and is not in the officer list")
    void anApplicantIsWaiting() {
        UUID applicant = newUser();
        officers.apply(applicant);

        MarketingOfficerService.Officer record = officers.get(applicant);
        assertThat(record.status()).isEqualTo("applied");
        assertThat(record.appliedAt()).isNotNull();
        assertThat(record.decidedAt()).isNull();

        // The role is granted at application, because that is what sends them to the officer portal
        // rather than the customer one. Holding it is not the same as being approved.
        assertThat(holdsOfficerRole(applicant)).isTrue();

        assertThat(officers.list())
                .as("the officers screen lists people who are working, not applicants")
                .noneMatch(officer -> officer.userId().equals(applicant));
        assertThat(officers.applications())
                .anyMatch(officer -> officer.userId().equals(applicant));
    }

    @Test
    @DisplayName("an applicant cannot be given customers until they are approved")
    void anApplicantCannotHoldCustomers() {
        UUID applicant = newUser();
        officers.apply(applicant);
        UUID distributorId = someDistributor();

        assertThatThrownBy(() -> officers.assign(distributorId, applicant))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not an approved marketing officer");

        officers.approve(applicant, new BigDecimal("0.0250"), admin());

        officers.assign(distributorId, applicant);
        assertThat(officerOf(distributorId)).isEqualTo(applicant);
    }

    @Test
    @DisplayName("approval records who decided, and the rate the administrator chose")
    void approvalRecordsTheDecision() {
        UUID applicant = newUser();
        officers.apply(applicant);
        UUID decidedBy = admin();

        MarketingOfficerService.Officer approved =
                officers.approve(applicant, new BigDecimal("0.0250"), decidedBy);

        assertThat(approved.status()).isEqualTo("approved");
        assertThat(approved.commissionRate()).isEqualByComparingTo("0.0250");
        assertThat(approved.decidedAt()).isNotNull();
        assertThat(decidedBy(applicant)).isEqualTo(decidedBy);
    }

    @Test
    @DisplayName("a second approval is refused rather than silently repeated")
    void approvingTwiceIsRefused() {
        UUID applicant = newUser();
        officers.apply(applicant);
        officers.approve(applicant, null, admin());

        // Two administrators with the queue open should not both be told they approved it.
        assertThatThrownBy(() -> officers.approve(applicant, null, admin()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been decided");
    }

    @Test
    @DisplayName("a rejection keeps the record, carries its reason, and releases the customers")
    void rejectionReleasesCustomers() {
        UUID officer = newUser();
        officers.apply(officer);
        officers.approve(officer, null, admin());

        UUID distributorId = someDistributor();
        officers.assign(distributorId, officer);

        officers.reject(officer, "Left the company.", admin());

        MarketingOfficerService.Officer record = officers.get(officer);
        assertThat(record.status()).isEqualTo("rejected");
        assertThat(record.rejectionReason()).isEqualTo("Left the company.");

        // Released rather than left attached: commission must stop being credited to somebody who
        // is no longer an officer.
        assertThat(officerOf(distributorId)).isNull();

        assertThat(officers.applications())
                .as("a rejected applicant is not back in the queue")
                .noneMatch(candidate -> candidate.userId().equals(officer));
    }

    @Test
    @DisplayName("a rejection without a reason is refused — the applicant is shown it")
    void rejectionNeedsAReason() {
        UUID applicant = newUser();
        officers.apply(applicant);

        assertThatThrownBy(() -> officers.reject(applicant, "   ", admin()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Say why");
    }

    @Test
    @DisplayName("an administrator enrolling an account approves it in the same act")
    void enrolmentIsAlreadyApproved() {
        UUID account = newUser();
        UUID by = admin();

        officers.enrol(account, new BigDecimal("0.0150"), by);

        MarketingOfficerService.Officer record = officers.get(account);
        assertThat(record.status())
                .as("no queue for somebody the approver just created")
                .isEqualTo("approved");
        assertThat(decidedBy(account)).isEqualTo(by);
        assertThat(officers.applications())
                .noneMatch(officer -> officer.userId().equals(account));
    }

    @Test
    @DisplayName("the front door creates an account holding the officer role")
    void theFrontDoorGrantsTheRole() {
        String email = "officer-" + UUID.randomUUID() + "@test.local";

        Optional<UUID> created =
                accounts.registerSelfService(
                        "Applicant", email, null, "Officer123", "MARKETING_OFFICER", "203.0.113.7");

        assertThat(created).isPresent();
        assertThat(holdsOfficerRole(created.get())).isTrue();

        // Vague on purpose, and empty rather than the existing id: a caller handed that id could
        // attach its own application to somebody else's account.
        assertThat(
                        accounts.registerSelfService(
                                "Somebody else",
                                email,
                                null,
                                "Officer123",
                                "MARKETING_OFFICER",
                                "203.0.113.8"))
                .as("already taken, and the caller is told nothing it could misuse")
                .isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private UUID newUser() {
        AppUser user = new AppUser();
        user.setEmail("officer-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Officer fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }

    private UUID admin() {
        return newUser();
    }

    /** A distributor row to assign. The referral tree is irrelevant here, only the column is. */
    private UUID someDistributor() {
        UUID userId = newUser();
        return jdbc.queryForObject(
                """
                INSERT INTO distributor (user_id, business_id, status)
                VALUES (?, ?, 'active')
                RETURNING id
                """,
                UUID.class,
                userId,
                businessId());
    }

    /** Positional ids are allocated by the tree; this only has to be unique and well-formed. */
    private String businessId() {
        return "9" + String.format("%06d", Math.abs(System.nanoTime()) % 1_000_000L);
    }

    private boolean holdsOfficerRole(UUID userId) {
        Integer held =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM user_role ur
                          JOIN app_role r ON r.id = ur.role_id
                         WHERE ur.user_id = ? AND r.code = 'MARKETING_OFFICER'
                        """,
                        Integer.class,
                        userId);
        return held != null && held > 0;
    }

    private UUID decidedBy(UUID userId) {
        return jdbc.queryForObject(
                "SELECT decided_by FROM marketing_officer WHERE user_id = ?", UUID.class, userId);
    }

    private UUID officerOf(UUID distributorId) {
        return jdbc.queryForObject(
                "SELECT marketing_officer_id FROM distributor WHERE id = ?",
                UUID.class,
                distributorId);
    }
}
