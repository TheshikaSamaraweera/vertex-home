package com.democode.mlmsittu.rewards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService.OpeningStock;
import com.democode.mlmsittu.catalogue.internal.service.ItemService.ItemDetails;
import com.democode.mlmsittu.catalogue.internal.service.ItemSetService;
import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import com.democode.mlmsittu.hierarchy.api.StageProgress;
import com.democode.mlmsittu.hierarchy.internal.DistributorService;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.portal.api.PortalAccess;
import com.democode.mlmsittu.portal.internal.PortalService;
import com.democode.mlmsittu.rewards.api.RewardDelivery.ReceiveMethodChoice;
import com.democode.mlmsittu.rewards.api.RewardStatus;
import com.democode.mlmsittu.rewards.internal.RewardAdminService;
import com.democode.mlmsittu.rewards.internal.RewardTrackingService;
import com.democode.mlmsittu.rewards.internal.domain.RewardEntitlement;
import com.democode.mlmsittu.rewards.internal.repo.RewardEntitlementRepository;
import com.democode.mlmsittu.shared.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * An issued pack's journey: a tracking number at issue, the customer choosing once how to receive
 * it, the office moving it on, and the last stage closing the business account.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Reward pack tracking")
class RewardTrackingTest {

    private static final String ADMIN_PASSWORD = "admin-password-123";

    @Autowired private ItemProvisioningService items;
    @Autowired private ItemSetService itemSets;
    @Autowired private DistributorService distributors;
    @Autowired private ReferralHierarchy hierarchy;
    @Autowired private RewardAdminService rewards;
    @Autowired private RewardTrackingService tracking;
    @Autowired private RewardStatus rewardStatus;
    @Autowired private RewardEntitlementRepository entitlements;
    @Autowired private PortalService portal;
    @Autowired private LocationDirectory locations;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID store;
    private UUID admin;

    @BeforeEach
    void setUp() {
        store = locations.defaultLocation().id();
        admin = newUser("Tracking admin", ADMIN_PASSWORD);
    }

    @Test
    @DisplayName("issuing starts tracking: a number, the first stage, and a history entry")
    void issuingStartsTracking() {
        UUID distributor = issuedDistributor();

        var snapshot = rewardStatus.forDistributor(distributor).orElseThrow();

        assertThat(snapshot.tracking().trackingNumber()).matches("PK-\\d{4}-\\d{6}");
        assertThat(snapshot.tracking().stage()).isEqualTo(RewardEntitlement.AWAITING_METHOD);
        assertThat(snapshot.tracking().receiveMethod()).isNull();
        assertThat(snapshot.tracking().history()).hasSize(1);
        assertThat(snapshot.items()).singleElement().satisfies(line -> assertThat(line.quantity()).isEqualTo(2));
    }

    @Test
    @DisplayName("the customer chooses once; a second choice is refused")
    void theCustomerChoosesOnce() {
        UUID distributor = issuedDistributor();

        tracking.chooseReceiveMethod(distributor, delivery("12 Galle Road, Colombo 03", "0771234567"));

        var after = rewardStatus.forDistributor(distributor).orElseThrow().tracking();
        assertThat(after.stage()).isEqualTo(RewardEntitlement.PREPARING);
        assertThat(after.receiveMethod()).isEqualTo(RewardEntitlement.DELIVERY);
        assertThat(after.deliveryContact()).isEqualTo("0771234567");

        assertThatThrownBy(
                        () ->
                                tracking.chooseReceiveMethod(
                                        distributor,
                                        new ReceiveMethodChoice("pickup", store, null, null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "RECEIVE_METHOD_LOCKED");
    }

    @Test
    @DisplayName("the office changes a chosen method only with the right password")
    void changingAMethodNeedsThePassword() {
        UUID distributor = issuedDistributor();
        UUID entitlement = entitlementOf(distributor);
        tracking.chooseReceiveMethod(distributor, delivery("Old address", "0771234567"));
        var pickup = new ReceiveMethodChoice("pickup", store, null, null);

        assertThatThrownBy(() -> tracking.setReceiveMethod(entitlement, pickup, null, admin))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PASSWORD_REQUIRED");
        assertThatThrownBy(() -> tracking.setReceiveMethod(entitlement, pickup, "wrong", admin))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PASSWORD_INCORRECT");
        assertThat(entitlements.findById(entitlement).orElseThrow().getReceiveMethod())
                .as("a refused change leaves the customer's choice alone")
                .isEqualTo(RewardEntitlement.DELIVERY);

        var view = tracking.setReceiveMethod(entitlement, pickup, ADMIN_PASSWORD, admin);

        assertThat(view.tracking().receiveMethod()).isEqualTo(RewardEntitlement.PICKUP);
        assertThat(view.tracking().pickupLocationId()).isEqualTo(store);
        assertThat(view.tracking().deliveryAddress()).as("pickup keeps no address").isNull();
    }

    @Test
    @DisplayName("the office may add a missing method without a password")
    void addingAMethodNeedsNoPassword() {
        UUID entitlement = entitlementOf(issuedDistributor());

        var view =
                tracking.setReceiveMethod(
                        entitlement, new ReceiveMethodChoice("pickup", store, null, null), null, admin);

        assertThat(view.tracking().stage()).isEqualTo(RewardEntitlement.PREPARING);
    }

    @Test
    @DisplayName("a pack cannot move on until somebody says how it will be received")
    void stagesNeedAMethod() {
        UUID entitlement = entitlementOf(issuedDistributor());

        assertThatThrownBy(
                        () -> tracking.changeStage(entitlement, RewardEntitlement.DISPATCHED, null, admin))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "RECEIVE_METHOD_REQUIRED");
        assertThatThrownBy(() -> tracking.complete(entitlement, store, null, admin))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "RECEIVE_METHOD_REQUIRED");
    }

    @Test
    @DisplayName("completing hands it over, closes the business account, and is final")
    void completingClosesTheAccount() {
        UUID distributor = issuedDistributor();
        UUID entitlement = entitlementOf(distributor);
        UUID userId = hierarchy.get(distributor).userId();
        tracking.chooseReceiveMethod(distributor, new ReceiveMethodChoice("pickup", store, null, null));
        tracking.changeStage(entitlement, RewardEntitlement.DISPATCHED, null, admin);

        var done = tracking.complete(entitlement, store, "Signed for at the counter", admin);

        assertThat(done.tracking().stage()).isEqualTo(RewardEntitlement.COMPLETED);
        assertThat(done.tracking().handoverLocationId()).isEqualTo(store);
        assertThat(done.tracking().completedByName()).isEqualTo("Tracking admin");
        assertThat(done.tracking().history())
                .extracting(RewardStatus.TrackingStep::stage)
                .containsExactly(
                        RewardEntitlement.AWAITING_METHOD,
                        RewardEntitlement.PREPARING,
                        RewardEntitlement.DISPATCHED,
                        RewardEntitlement.COMPLETED);

        // The account closes, but its history stays readable.
        var view = portal.viewFor(userId);
        assertThat(view.access()).isEqualTo(PortalAccess.COMPLETED);
        assertThat(view.businessId()).isNotBlank();
        assertThat(view.reward().tracking().stage()).isEqualTo(RewardEntitlement.COMPLETED);
        assertThatThrownBy(() -> portal.requireActiveDistributor(userId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "BUSINESS_ACCOUNT_COMPLETED");

        // Nothing moves a completed pack.
        assertThatThrownBy(
                        () -> tracking.changeStage(entitlement, RewardEntitlement.PREPARING, null, admin))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "REWARD_COMPLETED");
    }

    @Test
    @DisplayName("the tracking list holds issued packs only")
    void theTrackingListIsIssuedPacks() {
        UUID issued = entitlementOf(issuedDistributor());
        UUID waiting = entitlementOf(completedDistributor(newPack()));

        List<UUID> tracked =
                rewards.listTracked(null).stream().map(RewardAdminService.RewardEntitlementView::id).toList();

        assertThat(tracked).contains(issued).doesNotContain(waiting);
    }

    // ==============================================================================
    // Fixtures
    // ==============================================================================

    private static ReceiveMethodChoice delivery(String address, String contact) {
        return new ReceiveMethodChoice("delivery", null, address, contact);
    }

    private UUID issuedDistributor() {
        UUID distributor = completedDistributor(newPack());
        rewards.issue(entitlementOf(distributor), store, null, admin);
        return distributor;
    }

    private UUID entitlementOf(UUID distributor) {
        return entitlements.findByDistributorId(distributor).orElseThrow().getId();
    }

    private UUID completedDistributor(UUID pack) {
        UUID root = approvedDistributor(null, pack);
        for (int i = 0; i < StageProgress.TOTAL_STAGES; i++) {
            approvedDistributor(root, null);
        }
        return root;
    }

    private UUID approvedDistributor(UUID referrer, UUID pack) {
        UUID distributorId =
                distributors.createPending(newUser("Tracking fixture", "x-" + UUID.randomUUID()), referrer);
        distributors.attachToReferrer(distributorId, referrer);
        distributors.recordChosenPack(distributorId, pack);
        return distributorId;
    }

    private UUID newPack() {
        Item item =
                items.createWithOpeningStock(
                        "TRK-" + UUID.randomUUID(),
                        new ItemDetails(
                                "Tracked chair",
                                null,
                                null,
                                new BigDecimal("10.00"),
                                new BigDecimal("20.00"),
                                null,
                                null,
                                0),
                        new OpeningStock(store, 10),
                        admin);
        return itemSets
                .create(
                        "TRK-" + UUID.randomUUID().toString().substring(0, 8),
                        "Tracked pack",
                        null,
                        new BigDecimal("500.00"),
                        null,
                        List.of(new ItemSetService.ComponentRequest(item.getId(), 2)))
                .getId();
    }

    private UUID newUser(String fullName, String password) {
        AppUser user = new AppUser();
        user.setEmail("tracking-" + UUID.randomUUID() + "@test.local");
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }
}
