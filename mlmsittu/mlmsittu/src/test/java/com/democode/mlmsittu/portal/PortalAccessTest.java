package com.democode.mlmsittu.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.hierarchy.internal.DistributorService;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.onboarding.internal.registration.RegistrationService;
import com.democode.mlmsittu.onboarding.internal.registration.RegistrationService.SubmissionRequest;
import com.democode.mlmsittu.portal.api.PortalAccess;
import com.democode.mlmsittu.hierarchy.api.StageProgress;
import com.democode.mlmsittu.portal.api.PortalView;
import com.democode.mlmsittu.portal.internal.PortalService;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The two rules the distributor portal exists to enforce.
 *
 * <ol>
 *   <li><b>Nothing opens until a registration is approved.</b>
 *   <li><b>One level up, one level down.</b> A distributor sees their own parent and their own
 *       children, and no further in either direction.
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Distributor portal")
class PortalAccessTest {

    @Autowired private PortalService portal;
    @Autowired private RegistrationService registrations;
    @Autowired private DistributorService distributors;
    @Autowired private AppUserRepository users;
    @Autowired private DocumentVault vault;
    @Autowired private PasswordEncoder passwordEncoder;

    // ==============================================================================
    // The gate
    // ==============================================================================

    @Test
    @DisplayName("a fresh account is told to register, and shown nothing else")
    void newAccountMustRegister() {
        UUID user = newUser();

        PortalView view = portal.viewFor(user);

        assertThat(view.access()).isEqualTo(PortalAccess.REGISTRATION_REQUIRED);
        assertThat(view.fullName()).isNotBlank();
        assertThat(view.joinedAt()).isNotNull();

        // Nothing that only an approved distributor has.
        assertThat(view.businessId()).isNull();
        assertThat(view.distributorId()).isNull();
        assertThat(view.stages()).isNull();
        assertThat(view.parent()).isNull();
        assertThat(view.children()).isEmpty();
        assertThat(view.registration()).isNull();
    }

    @Test
    @DisplayName("after submitting, the portal shows the application and still nothing else")
    void submittedAccountIsPending() {
        UUID referrer = activeDistributor();
        UUID applicant = newUser();
        submit(applicant, referrer);

        PortalView view = portal.viewFor(applicant);

        assertThat(view.access()).isEqualTo(PortalAccess.PENDING_REVIEW);
        assertThat(view.registration()).isNotNull();
        assertThat(view.registration().status()).isEqualTo("submitted");
        assertThat(view.registration().underReview()).as("nobody has picked it up yet").isFalse();
        assertThat(view.registration().timeline()).isNotEmpty();

        assertThat(view.businessId()).isNull();
        assertThat(view.stages()).isNull();
    }

    @Test
    @DisplayName("a claimed application says somebody is looking at it")
    void claimedApplicationSaysSo() {
        UUID referrer = activeDistributor();
        UUID applicant = newUser();
        UUID registration = submit(applicant, referrer);

        registrations.claim(registration, newUser());

        assertThat(portal.viewFor(applicant).registration().underReview())
                .as("the difference between 'nobody has looked' and 'somebody is looking'")
                .isTrue();
    }

    @Test
    @DisplayName("a rejection that allows resubmission carries the reviewer's comments")
    void rejectionExplainsItself() {
        UUID referrer = activeDistributor();
        UUID applicant = newUser();
        UUID registration = submit(applicant, referrer);

        UUID reviewer = newUser();
        registrations.claim(registration, reviewer);
        registrations.reject(registration, reviewer, "ILLEGIBLE_NIC", "The scan is too dark to read.", true);

        PortalView view = portal.viewFor(applicant);

        assertThat(view.access()).isEqualTo(PortalAccess.CHANGES_REQUESTED);
        assertThat(view.registration().rejectionReason()).isEqualTo("ILLEGIBLE_NIC");
        assertThat(view.registration().rejectionNote()).contains("too dark");
        assertThat(view.businessId()).as("still no access").isNull();
    }

    @Test
    @DisplayName("a final rejection is distinguishable from one that can be fixed")
    void finalRejectionIsDifferent() {
        UUID referrer = activeDistributor();
        UUID applicant = newUser();
        UUID registration = submit(applicant, referrer);

        UUID reviewer = newUser();
        registrations.claim(registration, reviewer);
        registrations.reject(registration, reviewer, "INELIGIBLE", "Does not meet the criteria.", false);

        assertThat(portal.viewFor(applicant).access()).isEqualTo(PortalAccess.REJECTED);
    }

    @Test
    @DisplayName("approval is what opens the portal")
    void approvalOpensEverything() {
        UUID referrer = activeDistributor();
        UUID applicant = newUser();
        UUID registration = submit(applicant, referrer);

        UUID reviewer = newUser();
        registrations.claim(registration, reviewer);
        registrations.approve(registration, reviewer);

        PortalView view = portal.viewFor(applicant);

        assertThat(view.access()).isEqualTo(PortalAccess.ACTIVE);
        // A positional identifier: the parent's, with this person's seat appended. Asserting the
        // shape rather than a literal, because which seat they land in depends on the fixture.
        assertThat(view.businessId()).matches("^[1-9][06-9]*[1-5]*$");
        assertThat(view.distributorId()).isNotNull();
        assertThat(view.approvedAt()).isNotNull();
        assertThat(view.stages()).isNotNull();
        assertThat(view.stages().totalStages()).isEqualTo(StageProgress.TOTAL_STAGES);
        assertThat(view.parent()).as("they were referred by somebody").isNotNull();
    }

    @Test
    @DisplayName("the referrals endpoint is refused until approval, not merely empty")
    void referralsAreRefusedBeforeApproval() {
        UUID applicant = newUser();

        assertThatThrownBy(() -> portal.directReferrals(applicant))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown -> {
                            ApiException failure = (ApiException) thrown;
                            assertThat(failure.getCode()).isEqualTo("REGISTRATION_NOT_APPROVED");
                            assertThat(failure.getStatus().value())
                                    .as("403, not an empty list — an empty list looks like an answer")
                                    .isEqualTo(403);
                        });
    }

    // ==============================================================================
    // One level up, one level down
    // ==============================================================================

    @Test
    @DisplayName("a distributor sees their parent and their children — and nobody else")
    void visibilityStopsAtOneLevel() {
        // grandparent -> parent -> me -> child
        UUID grandparentUser = newUser();
        UUID grandparent = distributors.createPending(grandparentUser, null);
        distributors.attachToReferrer(grandparent, null);

        UUID parentUser = newUser();
        UUID parent = distributors.createPending(parentUser, grandparent);
        distributors.attachToReferrer(parent, grandparent);

        UUID meUser = newUser();
        UUID me = distributors.createPending(meUser, parent);
        distributors.attachToReferrer(me, parent);

        UUID childUser = newUser();
        UUID child = distributors.createPending(childUser, me);
        distributors.attachToReferrer(child, me);

        UUID grandchildUser = newUser();
        UUID grandchild = distributors.createPending(grandchildUser, child);
        distributors.attachToReferrer(grandchild, child);

        PortalView view = portal.viewFor(meUser);

        assertThat(view.parent().id()).isEqualTo(parent);
        assertThat(view.children()).extracting(DistributorNode::id).containsExactly(child);

        // The two that must not be reachable.
        assertThat(view.parent().referredBy())
                .as("the parent's own parent is an id, not a resolved node — no grandparent data")
                .isEqualTo(grandparent);
        assertThat(view.children())
                .as("the grandchild is not in the response at all")
                .extracting(DistributorNode::id)
                .doesNotContain(grandchild);
    }

    @Test
    @DisplayName("a root distributor has no parent, and says so rather than failing")
    void rootHasNoParent() {
        UUID rootUser = newUser();
        UUID root = distributors.createPending(rootUser, null);
        distributors.attachToReferrer(root, null);

        PortalView view = portal.viewFor(rootUser);

        assertThat(view.access()).isEqualTo(PortalAccess.ACTIVE);
        assertThat(view.parent()).isNull();
    }

    @Test
    @DisplayName("stages track the direct referral count, which is what unlocks a level")
    void stagesFollowReferrals() {
        UUID meUser = newUser();
        UUID me = distributors.createPending(meUser, null);
        distributors.attachToReferrer(me, null);

        assertThat(portal.viewFor(meUser).stages().stagesCompleted()).isZero();

        for (int i = 1; i <= StageProgress.TOTAL_STAGES; i++) {
            UUID childUser = newUser();
            UUID child = distributors.createPending(childUser, me);
            distributors.attachToReferrer(child, me);

            PortalView view = portal.viewFor(meUser);
            assertThat(view.stages().stagesCompleted()).isEqualTo(i);
            assertThat(view.children()).hasSize(i);
        }

        assertThat(portal.viewFor(meUser).stages().bonusStageEligible())
                .as("completing every stage unlocks the bonus")
                .isTrue();
    }

    // ==============================================================================
    // Fixtures
    // ==============================================================================

    private UUID activeDistributor() {
        UUID user = newUser();
        UUID distributor = distributors.createPending(user, null);
        distributors.attachToReferrer(distributor, null);
        return distributor;
    }

    private UUID submit(UUID applicant, UUID referrerDistributorId) {
        String referrerBusinessId = distributors.get(referrerDistributorId).businessId();

        var nic = vault.store(smallJpeg(), "image/jpeg", "nic", applicant);
        var slip = vault.store(smallJpeg(), "image/jpeg", "bank_slip", applicant);

        return registrations.submit(
                applicant,
                new SubmissionRequest(
                        "1990" + System.nanoTime() % 100_000_000L,
                        nic.id(),
                        slip.id(),
                        referrerBusinessId,
                        "12 Galle Road, Colombo 03",
                        "Commercial Bank",
                        "Colombo",
                        "1234567890",
                        null));
    }

    private UUID newUser() {
        AppUser user = new AppUser();
        user.setEmail("portal-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Portal fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        return users.saveAndFlush(user).getId();
    }

    private static byte[] smallJpeg() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpg", output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
