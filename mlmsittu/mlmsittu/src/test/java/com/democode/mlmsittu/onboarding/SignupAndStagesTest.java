package com.democode.mlmsittu.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import com.democode.mlmsittu.hierarchy.api.StageProgress;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.identity.internal.service.AccountSignupService;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.notify.NotificationSender;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** P4-05 email verification, and §0.2 stage progression. */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Signup and enrolment stages")
class SignupAndStagesTest {

    /**
     * Clears the shared rate-limit counters before each test.
     *
     * <p>Phase 7 moved rate limiting into the database so it holds across instances and survives a
     * restart. That is the point of it — and it means the counters also outlive a test context.
     * Signup allows five accounts per hour per IP; every test in the suite comes from the same
     * address, so without this the later ones are refused with {@code SIGNUP_RATE_LIMITED} and the
     * failure looks like a signup bug rather than an exhausted quota.
     */
    @BeforeEach
    void clearRateLimits() {
        jdbc.update("DELETE FROM rate_limit_attempt");
    }


    /**
     * Distinct phone numbers per test.
     *
     * <p>A number is now unique across the whole table, and the test database is not reset between
     * runs, so a hard-coded number passes once and then fails forever with a duplicate — which
     * reads like a bug in the code under test rather than in the test.
     */
    private static final java.util.concurrent.atomic.AtomicInteger RANDOM_SUFFIX =
            new java.util.concurrent.atomic.AtomicInteger(
                    new java.security.SecureRandom().nextInt(1_000_000));

    @Autowired private AccountSignupService signup;
    @Autowired private ReferralHierarchy hierarchy;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    /** Spied so the test can read the link out of the message that would have been sent. */
    @MockitoSpyBean private NotificationSender notifications;

    private static final Pattern LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

    // ==============================================================================
    // P4-05 · email verification
    // ==============================================================================

    @Test
    @DisplayName("an account is usable the moment it is created")
    void registrationIsImmediatelyActive() {
        String email = "signup-" + UUID.randomUUID() + "@test.local";
        signup.register("Nimal Perera", email, "+94770000000", "correct-horse-battery", "127.0.0.1");

        AppUser created = users.findByEmail(email).orElseThrow();
        assertThat(created.statusValue())
                .as("no verification step stands between registering and signing in")
                .isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("an email address alone is enough")
    void emailOnlyIsAccepted() {
        String email = "email-only-" + UUID.randomUUID() + "@test.local";
        signup.register("Email Only", email, null, "correct-horse-battery", "127.0.0.11");

        AppUser created = users.findByEmail(email).orElseThrow();
        assertThat(created.getMobile()).isNull();
    }

    @Test
    @DisplayName("a phone number alone is enough — the case the whole change exists for")
    void phoneOnlyIsAccepted() {
        // Most of this client's customers have no email address at all. Before this, the office
        // had to invent one for them.
        String mobile = "077" + (1000000 + RANDOM_SUFFIX.incrementAndGet());
        signup.register("Phone Only", null, mobile, "correct-horse-battery", "127.0.0.12");

        AppUser created = users.findByMobile("+9477" + mobile.substring(3)).orElseThrow();
        assertThat(created.getEmail()).isNull();
    }

    @Test
    @DisplayName("however the number is written, it is stored one way")
    void phoneNumbersAreNormalised() {
        int suffix = 1000000 + RANDOM_SUFFIX.incrementAndGet();
        signup.register("Spaced Out", null, "077 " + suffix, "correct-horse-battery", "127.0.0.13");

        // Registered with spaces and a trunk zero; found by the canonical form. If these two ever
        // disagree, a customer who registered at the desk cannot log in and nothing says why.
        assertThat(users.findByMobile("+9477" + suffix)).isPresent();
    }

    @Test
    @DisplayName("neither an email nor a number is refused")
    void anIdentifierIsRequired() {
        assertThatThrownBy(
                        () ->
                                signup.register(
                                        "Anonymous", null, null, "correct-horse-battery",
                                        "127.0.0.14"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("IDENTIFIER_REQUIRED"));
    }

    @Test
    @DisplayName("a phone number identifies one account, so the second is refused")
    void duplicatePhoneIsRejectedAtTheDesk() {
        int suffix = 1000000 + RANDOM_SUFFIX.incrementAndGet();
        String mobile = "077" + suffix;
        signup.registerOnBehalf("First Holder", null, mobile, "correct-horse-battery");

        assertThatThrownBy(
                        () ->
                                signup.registerOnBehalf(
                                        "Second Holder", null, mobile, "correct-horse-battery"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .as("named precisely, so the clerk looks for the right record")
                                        .isEqualTo("MOBILE_ALREADY_REGISTERED"));
    }


    @Test
    @DisplayName("signing up with an address already in use reveals nothing")
    void duplicateSignupIsIndistinguishable() {
        String email = "dupe-" + UUID.randomUUID() + "@test.local";
        signup.register("First Person", email, null, "correct-horse-battery", "127.0.0.4");

        // No exception, and no second account — the caller cannot tell the difference.
        signup.register("Second Person", email, null, "different-password", "127.0.0.4");

        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM app_user WHERE lower(email) = ?", Integer.class, email);
        assertThat(count).isEqualTo(1);
        assertThat(users.findByEmail(email).orElseThrow().getFullName()).isEqualTo("First Person");
    }

    // ==============================================================================
    // §0.2 · stage progression
    // ==============================================================================

    @Test
    @DisplayName("§0.2 · one stage per referral, bonus eligibility when they are all done")
    void stagesTrackReferrals() {
        UUID parent = activateRoot();

        assertThat(progress(parent).stagesCompleted()).isZero();
        assertThat(progress(parent).bonusStageEligible()).isFalse();

        // Driven off TOTAL_STAGES rather than a literal, so moving the programme from four
        // stages to five (28 Aug 2026) meant this test exercised five without being rewritten.
        int total = StageProgress.TOTAL_STAGES;
        for (int expected = 1; expected <= total; expected++) {
            attachChild(parent);
            StageProgress current = progress(parent);
            assertThat(current.stagesCompleted()).as("after referral %d", expected).isEqualTo(expected);
            assertThat(current.stagesRemaining()).isEqualTo(total - expected);
            assertThat(current.bonusStageEligible()).isEqualTo(expected == total);
        }

        assertThat(progress(parent).allStagesCompletedAt()).isNotNull();

        // Every unlock is recorded against the child that caused it.
        List<String> events =
                jdbc.queryForList(
                        "SELECT event_type FROM referral_stage_event WHERE distributor_id = ?"
                                + " ORDER BY id",
                        String.class,
                        parent);
        assertThat(events)
                .as("one unlock event per stage, in order")
                .containsOnly("unlocked")
                .hasSize(StageProgress.TOTAL_STAGES);

        Integer traced =
                jdbc.queryForObject(
                        "SELECT count(*) FROM referral_stage_event WHERE distributor_id = ?"
                                + " AND triggered_by_distributor_id IS NULL",
                        Integer.class,
                        parent);
        assertThat(traced).as("a stage that cannot be traced to who earned it is not evidence").isZero();
    }

    @Test
    @DisplayName("§0.2 · removing a referral revokes the stage it earned")
    void deletingAChildRevokesItsStage() {
        UUID parent = activateRoot();
        UUID first = attachChild(parent);
        for (int i = 1; i < StageProgress.TOTAL_STAGES; i++) {
            attachChild(parent);
        }

        assertThat(progress(parent).bonusStageEligible()).isTrue();

        hierarchy.softDelete(first);

        StageProgress after = progress(parent);
        assertThat(after.stagesCompleted()).isEqualTo(StageProgress.TOTAL_STAGES - 1);
        assertThat(after.bonusStageEligible())
                .as("bonus eligibility follows the stage count back down")
                .isFalse();
        assertThat(after.allStagesCompletedAt()).isNull();

        assertThat(
                        jdbc.queryForList(
                                "SELECT event_type FROM referral_stage_event WHERE distributor_id = ?"
                                        + " ORDER BY id",
                                String.class,
                                parent))
                .endsWith("revoked");
    }

    @Test
    @DisplayName("§0.2 · concurrent approvals cannot award the same stage twice")
    void stagesHoldUnderConcurrency() throws Exception {
        for (int round = 0; round < 10; round++) {
            UUID parent = activateRoot();

            int contenders = 8;
            List<UUID> pending = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                pending.add(hierarchy.createPending(newUser(), parent));
            }

            CountDownLatch startGun = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();

            List<Callable<Void>> tasks = new ArrayList<>();
            for (UUID candidate : pending) {
                tasks.add(
                        () -> {
                            startGun.await();
                            try {
                                hierarchy.attachToReferrer(candidate, parent);
                                accepted.incrementAndGet();
                            } catch (RuntimeException expected) {
                                // At capacity — the cap doing its job.
                            }
                            return null;
                        });
            }

            try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
                tasks.forEach(pool::submit);
                startGun.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(accepted.get()).isEqualTo(StageProgress.TOTAL_STAGES);

            // The point of the test: stages must equal referrals exactly. Awarding one twice, or
            // losing one to a lost update, is the failure that only shows up in a dispute.
            assertThat(progress(parent).stagesCompleted())
                    .as("stages in round %d", round)
                    .isEqualTo(StageProgress.TOTAL_STAGES);

            Integer unlockEvents =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM referral_stage_event WHERE distributor_id = ?"
                                    + " AND event_type = 'unlocked'",
                            Integer.class,
                            parent);
            assertThat(unlockEvents)
                    .as("one unlock event per stage, no duplicates")
                    .isEqualTo(StageProgress.TOTAL_STAGES);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private StageProgress progress(UUID distributorId) {
        return hierarchy.stageProgress(distributorId).orElseThrow();
    }

    private UUID activateRoot() {
        UUID distributorId = hierarchy.createPending(newUser(), null);
        hierarchy.attachToReferrer(distributorId, null);
        return distributorId;
    }

    private UUID attachChild(UUID parent) {
        UUID child = hierarchy.createPending(newUser(), parent);
        hierarchy.attachToReferrer(child, parent);
        return child;
    }

    private UUID newUser() {
        AppUser user = new AppUser();
        user.setEmail("stage-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Stage fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }

    /** Pulls the token out of the email body the sender was asked to deliver. */
}
