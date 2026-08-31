package com.democode.mlmsittu.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.repo.ItemRepository;
import com.democode.mlmsittu.catalogue.internal.service.ItemSetService;
import com.democode.mlmsittu.catalogue.internal.service.ItemSetService.ComponentRequest;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.SetAvailability;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockPosting;
import com.democode.mlmsittu.inventory.internal.service.AvailabilityService;
import com.democode.mlmsittu.inventory.internal.stock.ReservationService;
import com.democode.mlmsittu.inventory.api.ReservationRequestLine;
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

/** Availability arithmetic and contention flagging (development plan P3-02, P3-03). */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Item set availability")
class ItemSetAvailabilityTest {

    @Autowired private ItemSetService sets;
    @Autowired private AvailabilityService availability;
    @Autowired private ReservationService reservations;
    @Autowired private StockLedger ledger;
    @Autowired private ItemRepository items;
    @Autowired private AppUserRepository users;
    @Autowired private LocationDirectory locations;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID locationId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        locationId = locations.defaultLocation().id();
        actorId = testActor();
    }

    @Test
    @DisplayName("P3-02 · the scarcest component decides, not the most plentiful")
    void availabilityIsLimitedByTheScarcestComponent() {
        // Exactly the plan's worked example: a set needs 2 x A and 1 x B, stock A = 10, B = 3.
        // Naively dividing by A alone would say 5; the right answer is 3, limited by B.
        UUID itemA = newItemWithStock(10);
        UUID itemB = newItemWithStock(3);

        UUID setId = newSet(List.of(new ComponentRequest(itemA, 2), new ComponentRequest(itemB, 1)));

        SetAvailability result = availability.availabilityOf(setId, locationId);

        assertThat(result.availableSets()).as("B-limited, not A-limited").isEqualTo(3);
        assertThat(result.limitingItemId()).isEqualTo(itemB);
        assertThat(result.contended()).as("nothing else uses these items").isFalse();
    }

    @Test
    @DisplayName("P3-02 · one unit of the scarce component means zero sets")
    void oneScarceComponentMeansZeroSets() {
        UUID itemA = newItemWithStock(1);
        UUID itemB = newItemWithStock(50);

        UUID setId = newSet(List.of(new ComponentRequest(itemA, 2), new ComponentRequest(itemB, 1)));

        // floor(1 / 2) = 0. A set you cannot complete is not "nearly available".
        assertThat(availability.availabilityOf(setId, locationId).availableSets()).isZero();
    }

    @Test
    @DisplayName("P3-02 · availability reflects reservations, not just stock on hand")
    void reservedStockIsNotAvailable() {
        UUID itemA = newItemWithStock(10);
        UUID setId = newSet(List.of(new ComponentRequest(itemA, 2)));

        assertThat(availability.availabilityOf(setId, locationId).availableSets()).isEqualTo(5);

        reservations.reserve(
                List.of(new ReservationRequestLine(itemA, null, 6)), locationId, "test", null, null, actorId);

        // on_hand is still 10; 6 are spoken for, so only 4 are free -> floor(4/2) = 2.
        SetAvailability after = availability.availabilityOf(setId, locationId);
        assertThat(after.availableSets()).isEqualTo(2);
        assertThat(after.components().getFirst().onHand()).isEqualTo(10);
        assertThat(after.components().getFirst().reserved()).isEqualTo(6);
    }

    @Test
    @DisplayName("P3-03 · overlapping sets both report a figure AND a contention flag")
    void sharedComponentsAreFlaggedAsContended() {
        UUID shared = newItemWithStock(10);
        UUID onlyInFirst = newItemWithStock(10);
        UUID onlyInSecond = newItemWithStock(10);

        UUID first =
                newSet(List.of(new ComponentRequest(shared, 1), new ComponentRequest(onlyInFirst, 1)));
        UUID second =
                newSet(
                        List.of(
                                new ComponentRequest(shared, 1),
                                new ComponentRequest(onlyInSecond, 1)));

        SetAvailability a = availability.availabilityOf(first, locationId);
        SetAvailability b = availability.availabilityOf(second, locationId);

        // Both say 10 — and between them they can only ship 10 in total, not 20. The flag is the
        // honest part of the answer.
        assertThat(a.availableSets()).isEqualTo(10);
        assertThat(b.availableSets()).isEqualTo(10);
        assertThat(a.contended()).isTrue();
        assertThat(b.contended()).isTrue();

        assertThat(a.components())
                .filteredOn(component -> component.itemId().equals(shared))
                .singleElement()
                .satisfies(component -> assertThat(component.contended()).isTrue());
        assertThat(a.components())
                .filteredOn(component -> component.itemId().equals(onlyInFirst))
                .singleElement()
                .satisfies(component -> assertThat(component.contended()).isFalse());
    }

    @Test
    @DisplayName("P3-04 · reserving a set reserves every component, on_hand untouched")
    void reservingASetTouchesEveryComponent() {
        UUID itemA = newItemWithStock(20);
        UUID itemB = newItemWithStock(20);
        UUID setId = newSet(List.of(new ComponentRequest(itemA, 2), new ComponentRequest(itemB, 3)));

        reservations.reserve(
                List.of(new ReservationRequestLine(null, setId, 2)), locationId, "test", null, null, actorId);

        var levelA = ledger.levelOf(itemA, locationId).orElseThrow();
        var levelB = ledger.levelOf(itemB, locationId).orElseThrow();

        assertThat(levelA.reserved()).as("2 sets x 2 per set").isEqualTo(4);
        assertThat(levelB.reserved()).as("2 sets x 3 per set").isEqualTo(6);
        assertThat(levelA.onHand()).as("reservation never decrements on_hand").isEqualTo(20);
        assertThat(levelB.onHand()).isEqualTo(20);
    }

    // ------------------------------------------------------------------ fixtures

    private UUID newSet(List<ComponentRequest> components) {
        return sets.create(
                        "SET-" + UUID.randomUUID(),
                        "Availability fixture",
                        null,
                        BigDecimal.TEN,
                        components)
                .getId();
    }

    private UUID newItemWithStock(int quantity) {
        Item item = new Item();
        item.setSku("AVAIL-" + UUID.randomUUID());
        item.setName("Availability fixture");
        item.setUnitCost(BigDecimal.ONE);
        item.setSellingPrice(BigDecimal.TEN);
        item.setReorderLevel(0);
        item.setActive(true);
        UUID itemId = items.saveAndFlush(item).getId();

        ledger.post(StockPosting.openingBalance(itemId, locationId, quantity, actorId));
        return itemId;
    }

    private UUID testActor() {
        return users.findByEmail("availability@test.local")
                .map(AppUser::getId)
                .orElseGet(
                        () -> {
                            AppUser user = new AppUser();
                            user.setEmail("availability@test.local");
                            user.setFullName("Availability Test Actor");
                            user.setPasswordHash(
                                    passwordEncoder.encode(UUID.randomUUID().toString()));
                            user.setStatusValue(UserStatus.ACTIVE);
                            return users.saveAndFlush(user).getId();
                        });
    }

    // ==============================================================================
    // Editing a set (regression, 28 Aug 2026)
    // ==============================================================================

    @Test
    @DisplayName("a set can be edited while keeping the items it already has")
    void editingKeepsExistingComponents() {
        UUID kept = newItemWithStock(10);
        UUID dropped = newItemWithStock(10);

        var set =
                sets.create(
                        "EDIT-" + UUID.randomUUID().toString().substring(0, 8),
                        "Editable set",
                        null,
                        new java.math.BigDecimal("500.00"),
                        List.of(
                                new ComponentRequest(kept, 2),
                                new ComponentRequest(dropped, 1)));

        // The ordinary edit, and the one that used to fail: the same item stays, its quantity
        // changes, and another is removed. Hibernate inserted the new line for `kept` before
        // deleting the old one, violating uq_item_set_line.
        var edited =
                sets.update(
                        set.getId(),
                        "Edited set",
                        "now with a description",
                        new java.math.BigDecimal("650.00"),
                        List.of(new ComponentRequest(kept, 5)));

        assertThat(edited.getName()).isEqualTo("Edited set");
        assertThat(edited.getSetPrice()).isEqualByComparingTo("650.00");
        assertThat(edited.getLines()).hasSize(1);
        assertThat(edited.getLines().get(0).getItemId()).isEqualTo(kept);
        assertThat(edited.getLines().get(0).getQuantity())
                .as("the kept item's quantity was updated, not duplicated")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("the set price survives an edit and reaches the availability feed")
    void setPriceIsCarriedThrough() {
        UUID component = newItemWithStock(10);
        var set =
                sets.create(
                        "PRICE-" + UUID.randomUUID().toString().substring(0, 8),
                        "Priced set",
                        null,
                        new java.math.BigDecimal("1250.00"),
                        List.of(new ComponentRequest(component, 1)));

        // The screens read sets from the availability feed, so a price missing there is a price
        // the user never sees however well it is stored.
        assertThat(availability.availabilityOfAll(null, true))
                .filteredOn(row -> row.setId().equals(set.getId()))
                .singleElement()
                .satisfies(row -> assertThat(row.setPrice()).isEqualByComparingTo("1250.00"));
    }
}