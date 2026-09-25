package com.democode.mlmsittu.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.repo.ItemRepository;
import com.democode.mlmsittu.catalogue.internal.service.ItemSetService;
import com.democode.mlmsittu.catalogue.internal.service.ItemSetService.ComponentRequest;
import com.democode.mlmsittu.hierarchy.internal.DistributorService;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.onboarding.internal.registration.ReferralCardService;
import com.democode.mlmsittu.onboarding.internal.registration.RegistrationService;
import com.democode.mlmsittu.onboarding.internal.registration.RegistrationService.SubmissionRequest;
import com.democode.mlmsittu.portal.api.PortalView;
import com.democode.mlmsittu.portal.internal.PackCatalogueService;
import com.democode.mlmsittu.portal.internal.PortalService;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.notify.Notifications;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The customer-facing item pack catalogue, the reviewer's view of an application, and the
 * notification that tells reviewers one has arrived.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Customer packs and registration review")
class CustomerPacksAndReviewTest {

    @Autowired private PackCatalogueService packs;
    @Autowired private PortalService portal;
    @Autowired private RegistrationService registrations;
    @Autowired private ReferralCardService cards;
    @Autowired private DistributorService distributors;
    @Autowired private ItemSetService sets;
    @Autowired private ItemRepository items;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Notifications notifications;
    @Autowired private DocumentVault vault;
    @Autowired private PasswordEncoder passwordEncoder;

    // ==============================================================================
    // Item packs
    // ==============================================================================

    @Test
    @DisplayName("before registering, a customer sees every pack in full, pictures included")
    void everyPackIsOpenBeforeRegistering() {
        UUID picture = vault.store(smallJpeg(), "image/jpeg", "item", newUser()).id();
        UUID chair = newItem("Oak chair", "Solid oak, seats one", picture);
        UUID first = newPack("Pack A", chair);
        UUID second = newPack("Pack B", chair);

        var catalogue = packs.forCustomer(newUser());

        assertThat(catalogue.selectedPackId()).isNull();
        var mine = catalogue.packs().stream().filter(p -> Set.of(first, second).contains(p.id())).toList();
        assertThat(mine).hasSize(2).allSatisfy(pack -> assertThat(pack.locked()).isFalse());
        assertThat(mine.get(0).description()).isNotBlank();
        assertThat(mine.get(0).items())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.name()).isEqualTo("Oak chair");
                            assertThat(item.description()).isEqualTo("Solid oak, seats one");
                            assertThat(item.imageId()).isEqualTo(picture);
                            assertThat(item.quantity()).isEqualTo(2);
                        });
    }

    @Test
    @DisplayName("after registering with a pack, only that pack is open — the rest are withheld")
    void onlyTheChosenPackIsOpenAfterRegistering() {
        UUID chair = newItem("Teak chair", "Teak", null);
        UUID chosen = newPack("Chosen pack", chair);
        UUID other = newPack("Other pack", chair);
        UUID applicant = newUser();

        submit(applicant, chosen);
        var catalogue = packs.forCustomer(applicant);

        assertThat(catalogue.selectedPackId()).isEqualTo(chosen);
        assertThat(catalogue.packs().getFirst().id())
                .as("the chosen pack comes first")
                .isEqualTo(chosen);
        assertThat(catalogue.packs())
                .filteredOn(pack -> pack.id().equals(chosen))
                .singleElement()
                .satisfies(
                        pack -> {
                            assertThat(pack.selected()).isTrue();
                            assertThat(pack.locked()).isFalse();
                            assertThat(pack.items()).isNotEmpty();
                        });
        assertThat(catalogue.packs())
                .filteredOn(pack -> pack.id().equals(other))
                .singleElement()
                .satisfies(
                        pack -> {
                            // Still listed — name, picture, price — but the contents are not in
                            // the response at all, so nothing in the browser can reveal them.
                            assertThat(pack.locked()).isTrue();
                            assertThat(pack.items()).isEmpty();
                            assertThat(pack.description()).isNull();
                            assertThat(pack.name()).isEqualTo("Other pack");
                        });
    }

    // ==============================================================================
    // Review
    // ==============================================================================

    @Test
    @DisplayName("the bank account is masked for review, and both numbers are revealed only on request")
    void sensitiveNumbersAreHiddenUntilRevealed() {
        UUID applicant = newUser();
        UUID registration = submit(applicant, null);

        assertThat(registrations.get(registration).masked().bankAccountNumber())
                .isEqualTo("••••7890");

        var revealed = registrations.revealSensitive(registration, reviewer());
        assertThat(revealed.bankAccountNumber()).isEqualTo("1234567890");
        assertThat(revealed.nicNumber()).startsWith("1990").hasSize(12);

        // Nobody reveals their own.
        assertThatThrownBy(() -> registrations.revealSensitive(registration, applicant))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a new registration notifies every reviewer, but not the applicant")
    void reviewersAreToldAboutNewRegistrations() {
        UUID reviewer = reviewer();
        UUID applicant = newUser();

        submit(applicant, null);

        assertThat(notifications.list(reviewer, 20))
                .anyMatch(
                        n ->
                                n.kind().equals(Notifications.REGISTRATION_SUBMITTED)
                                        && n.body().contains("Packs fixture")
                                        && "/registrations".equals(n.link()));
        assertThat(notifications.list(applicant, 20))
                .noneMatch(n -> n.kind().equals(Notifications.REGISTRATION_SUBMITTED));
    }

    // ==============================================================================
    // Membership card
    // ==============================================================================

    @Test
    @DisplayName("an approved customer's portal carries their membership expiry date")
    void portalShowsTheExpiryDate() {
        UUID applicant = newUser();
        UUID registration = submit(applicant, null);
        UUID reviewer = reviewer();
        registrations.claim(registration, reviewer);
        registrations.approve(registration, reviewer);

        PortalView view = portal.viewFor(applicant);

        assertThat(view.expiresAt())
                .isEqualTo(distributors.findByUserId(applicant).orElseThrow().expiresAt())
                .isNotNull();
    }

    // ==============================================================================
    // Fixtures
    // ==============================================================================

    private UUID newItem(String name, String description, UUID imageId) {
        Item item = new Item();
        item.setSku("PACK-" + UUID.randomUUID());
        item.setName(name);
        item.setDescription(description);
        item.setImageId(imageId);
        item.setUnitCost(BigDecimal.ONE);
        item.setSellingPrice(BigDecimal.TEN);
        item.setReorderLevel(0);
        item.setActive(true);
        return items.saveAndFlush(item).getId();
    }

    private UUID newPack(String name, UUID component) {
        return sets.create(
                        "PK-" + UUID.randomUUID().toString().substring(0, 8),
                        name,
                        "Everything a new home needs",
                        new BigDecimal("50000.00"),
                        null,
                        List.of(new ComponentRequest(component, 2)))
                .getId();
    }

    /** A root registration: no referrer and no card, the path an administrator uses. */
    private UUID submit(UUID applicant, UUID itemSetId) {
        var nic = vault.store(smallJpeg(), "image/jpeg", "nic", applicant);
        var slip = vault.store(smallJpeg(), "image/jpeg", "bank_slip", applicant);
        return registrations.submit(
                applicant,
                new SubmissionRequest(
                        String.format("1990%08d", Math.abs(System.nanoTime()) % 100_000_000L),
                        nic.id(),
                        slip.id(),
                        null,
                        null,
                        "12 Galle Road, Colombo 03",
                        "Commercial Bank",
                        "Colombo",
                        "1234567890",
                        itemSetId));
    }

    private UUID reviewer() {
        UUID id = users.saveAndFlush(user("reviewer")).getId();
        jdbc.update(
                "INSERT INTO user_role (user_id, role_id) SELECT ?, id FROM app_role WHERE code = ?",
                id,
                "KYC_REVIEWER");
        return id;
    }

    private UUID newUser() {
        return users.saveAndFlush(user("packs")).getId();
    }

    private AppUser user(String prefix) {
        AppUser user = new AppUser();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Packs fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        return user;
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
