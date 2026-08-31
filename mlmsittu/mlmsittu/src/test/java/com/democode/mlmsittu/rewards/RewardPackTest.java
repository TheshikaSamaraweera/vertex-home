package com.democode.mlmsittu.rewards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService.OpeningStock;
import com.democode.mlmsittu.catalogue.internal.service.ItemService.ItemDetails;
import com.democode.mlmsittu.catalogue.internal.service.ItemSetService;
import com.democode.mlmsittu.hierarchy.api.StageProgress;
import com.democode.mlmsittu.hierarchy.internal.DistributorService;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.internal.location.LocationService;
import com.democode.mlmsittu.rewards.api.RewardStatus;
import com.democode.mlmsittu.rewards.internal.RewardAdminService;
import com.democode.mlmsittu.rewards.internal.domain.RewardEntitlement;
import com.democode.mlmsittu.rewards.internal.repo.RewardEntitlementRepository;
import com.democode.mlmsittu.shared.error.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The referral reward, now that §0.2 has substance (27 Aug 2026).
 *
 * <p>Four stages complete → eligible for the pack chosen at registration → an administrator issues
 * it out of a named store → the stock leaves. The value worth protecting is at the joins: nothing
 * grants itself, nothing is issued twice, and the pack either comes out of one store in full or
 * not at all.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Reward packs")
class RewardPackTest {

    @Autowired private ItemProvisioningService items;
    @Autowired private ItemSetService itemSets;
    @Autowired private DistributorService distributors;
    @Autowired private RewardAdminService rewards;
    @Autowired private RewardStatus rewardStatus;
    @Autowired private RewardEntitlementRepository entitlements;
    @Autowired private StockLedger ledger;
    @Autowired private LocationService locations;
    @Autowired private LocationDirectory locationDirectory;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID mainStore;

    @BeforeEach
    void setUp() {
        mainStore = locationDirectory.defaultLocation().id();
    }

    // ==============================================================================
    // Becoming eligible
    // ==============================================================================

    @Test
    @DisplayName("the last referral makes the pack claimable — and nothing before it does")
    void theFinalReferralIsWhatCounts() {
        Item widget = newItem(50);
        UUID pack = newPack(Map.of(widget.getId(), 2));
        UUID root = approvedDistributor(null, pack);

        // Everything short of the last one earns nothing. Driven off TOTAL_STAGES rather than a
        // literal, so raising the programme from four stages to five (28 Aug 2026) did not need
        // this test rewritten — it just tested five instead.
        for (int placed = 1; placed < StageProgress.TOTAL_STAGES; placed++) {
            approvedDistributor(root, null);
            assertThat(entitlements.findByDistributorId(root))
                    .as("nothing is owed after %d referral(s)", placed)
                    .isEmpty();
        }

        approvedDistributor(root, null);

        assertThat(entitlements.findByDistributorId(root))
                .get()
                .satisfies(
                        entitlement -> {
                            assertThat(entitlement.getStatus()).isEqualTo(RewardEntitlement.ELIGIBLE);
                            assertThat(entitlement.getItemSetId()).isEqualTo(pack);
                            assertThat(entitlement.getIssuedAt())
                                    .as("eligible is not issued")
                                    .isNull();
                        });
    }

    @Test
    @DisplayName("becoming eligible moves no stock")
    void eligibilityIsNotAnIssue() {
        Item widget = newItem(50);
        UUID pack = newPack(Map.of(widget.getId(), 2));
        long before = available(widget, mainStore);

        completedDistributor(pack);

        assertThat(available(widget, mainStore)).isEqualTo(before);
    }

    @Test
    @DisplayName("a distributor who chose no pack earns no entitlement")
    void noPackNoEntitlement() {
        UUID root = completedDistributor(null);
        assertThat(entitlements.findByDistributorId(root)).isEmpty();
    }

    // ==============================================================================
    // Issuing
    // ==============================================================================

    @Test
    @DisplayName("issuing takes every component out of the chosen store")
    void issuingMovesTheStock() {
        Item first = newItem(50);
        Item second = newItem(50);
        UUID pack = newPack(Map.of(first.getId(), 2, second.getId(), 3));
        UUID root = completedDistributor(pack);
        UUID actor = newUser("Issuing admin");

        long firstBefore = available(first, mainStore);
        long secondBefore = available(second, mainStore);

        var entitlement = entitlements.findByDistributorId(root).orElseThrow();
        var view = rewards.issue(entitlement.getId(), mainStore, "collected in person", actor);

        assertThat(view.status()).isEqualTo(RewardEntitlement.ISSUED);
        assertThat(view.issuedFromLocationId()).isEqualTo(mainStore);
        assertThat(available(first, mainStore)).isEqualTo(firstBefore - 2);
        assertThat(available(second, mainStore)).isEqualTo(secondBefore - 3);
    }

    @Test
    @DisplayName("every movement points back at the entitlement that caused it")
    void movementsAreTraceable() {
        Item widget = newItem(50);
        UUID pack = newPack(Map.of(widget.getId(), 4));
        UUID root = completedDistributor(pack);
        var entitlement = entitlements.findByDistributorId(root).orElseThrow();

        rewards.issue(entitlement.getId(), mainStore, null, newUser("Issuing admin"));

        assertThat(ledger.movementsFor("reward_entitlement", entitlement.getId()))
                .singleElement()
                .satisfies(
                        movement -> {
                            assertThat(movement.qtyDelta())
                                    .as("stock leaves, so the delta is negative")
                                    .isEqualTo(-4);
                            assertThat(movement.type().name()).isEqualTo("REWARD_ISSUE");
                            assertThat(movement.note()).contains("Reward pack");
                        });
    }

    @Test
    @DisplayName("the same pack cannot be issued twice")
    void issuingIsNotRepeatable() {
        Item widget = newItem(50);
        UUID pack = newPack(Map.of(widget.getId(), 1));
        UUID root = completedDistributor(pack);
        UUID actor = newUser("Issuing admin");
        var entitlement = entitlements.findByDistributorId(root).orElseThrow();

        rewards.issue(entitlement.getId(), mainStore, null, actor);

        assertThatThrownBy(() -> rewards.issue(entitlement.getId(), mainStore, null, actor))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "REWARD_ALREADY_ISSUED");
    }

    @Test
    @DisplayName("a store that is short of one component cannot supply the pack at all")
    void aPartialPackIsRefused() {
        Item plentiful = newItem(50);
        Item scarce = newItem(1);
        UUID pack = newPack(Map.of(plentiful.getId(), 2, scarce.getId(), 5));
        UUID root = completedDistributor(pack);
        UUID actor = newUser("Issuing admin");
        var entitlement = entitlements.findByDistributorId(root).orElseThrow();

        long plentifulBefore = available(plentiful, mainStore);

        assertThatThrownBy(() -> rewards.issue(entitlement.getId(), mainStore, null, actor))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PACK_NOT_AVAILABLE");

        assertThat(available(plentiful, mainStore))
                .as("the component that was in stock must not have moved either")
                .isEqualTo(plentifulBefore);
    }

    @Test
    @DisplayName("the admin view says which stores can fill it, and which cannot")
    void availabilityIsShownPerStore() {
        Item widget = newItem(6);
        UUID pack = newPack(Map.of(widget.getId(), 4));
        UUID root = completedDistributor(pack);
        UUID annex =
                locations
                        .create(
                                "RW-" + UUID.randomUUID().toString().substring(0, 8),
                                new LocationService.StoreDetails("Reward annex", null))
                        .getId();

        var view = rewards.get(entitlements.findByDistributorId(root).orElseThrow().getId());

        assertThat(view.anyStoreCanFulfil()).isTrue();
        assertThat(view.stores())
                .as("every active store is listed, not only the ones that can help")
                .anySatisfy(store -> assertThat(store.locationId()).isEqualTo(mainStore))
                .anySatisfy(store -> assertThat(store.locationId()).isEqualTo(annex));

        var main = view.stores().stream().filter(s -> s.locationId().equals(mainStore)).findFirst().orElseThrow();
        var empty = view.stores().stream().filter(s -> s.locationId().equals(annex)).findFirst().orElseThrow();

        assertThat(main.canFulfilWholePack()).isTrue();
        assertThat(empty.canFulfilWholePack()).isFalse();
        assertThat(empty.components())
                .singleElement()
                .satisfies(
                        component -> {
                            assertThat(component.required()).isEqualTo(4);
                            assertThat(component.available()).isZero();
                            assertThat(component.enough()).isFalse();
                        });
    }

    // ==============================================================================
    // What the distributor sees
    // ==============================================================================

    @Test
    @DisplayName("the distributor sees their pack, and then sees it issued")
    void theDistributorIsToldBothTimes() {
        Item widget = newItem(50);
        UUID pack = newPack(Map.of(widget.getId(), 2));
        UUID root = completedDistributor(pack);

        assertThat(rewardStatus.forDistributor(root))
                .get()
                .satisfies(
                        snapshot -> {
                            assertThat(snapshot.status()).isEqualTo(RewardEntitlement.ELIGIBLE);
                            assertThat(snapshot.itemSetId()).isEqualTo(pack);
                            assertThat(snapshot.issuedAt()).isNull();
                            assertThat(snapshot.issuedFromStore()).isNull();
                        });

        rewards.issue(
                entitlements.findByDistributorId(root).orElseThrow().getId(),
                mainStore,
                null,
                newUser("Issuing admin"));

        assertThat(rewardStatus.forDistributor(root))
                .get()
                .satisfies(
                        snapshot -> {
                            assertThat(snapshot.status()).isEqualTo(RewardEntitlement.ISSUED);
                            assertThat(snapshot.issuedAt()).isNotNull();
                            assertThat(snapshot.issuedFromStore()).isEqualTo("Main Warehouse");
                        });
    }

    @Test
    @DisplayName("a distributor with nothing owed is told nothing")
    void nothingOwedIsEmpty() {
        UUID root = approvedDistributor(null, newPack(Map.of(newItem(5).getId(), 1)));
        assertThat(rewardStatus.forDistributor(root)).isEmpty();
    }

    // ==============================================================================
    // Fixtures
    // ==============================================================================

    /** An approved distributor who has completed every stage. */
    private UUID completedDistributor(UUID pack) {
        UUID root = approvedDistributor(null, pack);
        for (int i = 0; i < StageProgress.TOTAL_STAGES; i++) {
            approvedDistributor(root, null);
        }
        return root;
    }

    private UUID approvedDistributor(UUID referrer, UUID pack) {
        UUID userId = newUser("Reward fixture");
        UUID distributorId = distributors.createPending(userId, referrer);
        distributors.attachToReferrer(distributorId, referrer);
        // Mirrors what registration approval does: the chosen pack lands on the distributor at the
        // one moment it is decided.
        distributors.recordChosenPack(distributorId, pack);
        return distributorId;
    }

    private UUID newPack(Map<UUID, Integer> components) {
        return itemSets
                .create(
                        "RWD-" + UUID.randomUUID().toString().substring(0, 8),
                        "Reward pack",
                        null,
                        new BigDecimal("500.00"),
                        components.entrySet().stream()
                                .map(
                                        entry ->
                                                new ItemSetService.ComponentRequest(
                                                        entry.getKey(), entry.getValue()))
                                .toList())
                .getId();
    }

    private Item newItem(int opening) {
        return items.createWithOpeningStock(
                "RWD-" + UUID.randomUUID(),
                new ItemDetails(
                        "Reward component",
                        null,
                        null,
                        new BigDecimal("10.00"),
                        new BigDecimal("20.00"),
                        null,
                        null,
                        0),
                new OpeningStock(mainStore, opening),
                newUser("Reward fixture"));
    }

    private long available(Item item, UUID locationId) {
        return ledger.levelOf(item.getId(), locationId).map(level -> (long) level.available()).orElse(0L);
    }

    private UUID newUser(String fullName) {
        AppUser user = new AppUser();
        user.setEmail("reward-" + UUID.randomUUID() + "@test.local");
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }

}
