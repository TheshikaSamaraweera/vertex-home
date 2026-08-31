package com.democode.mlmsittu.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService;
import com.democode.mlmsittu.catalogue.internal.service.ItemService;
import com.democode.mlmsittu.catalogue.internal.service.ItemService.ItemDetails;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService.OpeningStock;
import com.democode.mlmsittu.commerce.internal.service.PurchaseOrderService;
import com.democode.mlmsittu.commerce.internal.service.SupplierPriceService;
import com.democode.mlmsittu.commerce.internal.service.SupplierService;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.ReservationRequestLine;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockMovementType;
import com.democode.mlmsittu.inventory.api.StockReservations;
import com.democode.mlmsittu.shared.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The changes the client asked for after the first demo (16 Aug 2026).
 *
 * <p>Covers the parts that are decided below the controller. The field-level rule on retail and
 * wholesale prices lives in {@code CatalogueController} because it needs the caller's roles, and is
 * checked by hand through the UI.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Client change requests")
class ItemAndRoleChangesTest {

    @Autowired private ItemProvisioningService items;
    @Autowired private SupplierPriceService supplierPrices;
    @Autowired private SupplierService suppliers;
    @Autowired private PurchaseOrderService purchaseOrders;
    @Autowired private StockLedger ledger;
    @Autowired private StockReservations reservations;
    @Autowired private LocationDirectory locations;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RoleHierarchy roleHierarchy;

    private UUID locationId;
    private UUID actor;

    @BeforeEach
    void setUp() {
        locationId = locations.defaultLocation().id();
        actor = newUser();
    }

    // ==============================================================================
    // The admin role
    // ==============================================================================

    @Test
    @DisplayName("admin reaches every specialist role, and super admin reaches admin")
    void theHierarchyImpliesWhatItShould() {
        assertThat(reachableFrom("ROLE_ADMIN"))
                .contains(
                        "ROLE_ADMIN",
                        "ROLE_KYC_REVIEWER",
                        "ROLE_INVENTORY_CLERK",
                        "ROLE_PROCUREMENT_OFFICER",
                        "ROLE_FINANCE_OFFICER",
                        "ROLE_SUPPORT_AGENT");

        assertThat(reachableFrom("ROLE_SUPER_ADMIN")).contains("ROLE_ADMIN", "ROLE_INVENTORY_CLERK");
    }

    @Test
    @DisplayName("an admin cannot reach super admin — which is what keeps user management away")
    void adminIsNotSuperAdmin() {
        // The one privilege an admin does not get. User and role management asks for SUPER_ADMIN,
        // so this single assertion is what stops an admin granting themselves anything.
        assertThat(reachableFrom("ROLE_ADMIN")).doesNotContain("ROLE_SUPER_ADMIN");

        // A specialist role reaches itself and the synthetic STAFF, and nothing else. STAFF is what
        // the open read endpoints ask for, so this is also the assertion that a clerk can read the
        // catalogue while a distributor cannot.
        assertThat(reachableFrom("ROLE_INVENTORY_CLERK"))
                .containsExactlyInAnyOrder("ROLE_INVENTORY_CLERK", "ROLE_STAFF");

        // The role that must reach nothing at all. A distributor holds this and only this, which is
        // what keeps every staff screen closed to them.
        assertThat(reachableFrom("ROLE_DISTRIBUTOR")).containsExactly("ROLE_DISTRIBUTOR");
    }

    private List<String> reachableFrom(String authority) {
        return roleHierarchy
                .getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority(authority)))
                .stream()
                .map(granted -> granted.getAuthority())
                .toList();
    }

    // ==============================================================================
    // Item creation
    // ==============================================================================

    @Test
    @DisplayName("a new item is immediately sellable, because creation establishes its position")
    void creationEstablishesAStockPosition() {
        Item item = newItem("120.00", new OpeningStock(locationId, 40));

        var level = ledger.levelOf(item.getId(), locationId).orElseThrow();
        assertThat(level.onHand()).isEqualTo(40);

        // The opening balance went through the ledger, so reconciliation can account for it.
        var movements = ledger.movementHistory(item.getId(), locationId);
        assertThat(movements).hasSize(1);
        assertThat(movements.getFirst().type()).isEqualTo(StockMovementType.OPENING_BALANCE);
        assertThat(ledger.ledgerTotalFor(item.getId(), locationId)).isEqualTo(40);
    }

    @Test
    @DisplayName("an item opened at zero still has a position, so reserving fails honestly")
    void zeroOpeningStillCreatesTheRow() {
        Item item = newItem("50.00", new OpeningStock(locationId, 0));

        var level = ledger.levelOf(item.getId(), locationId);
        assertThat(level).as("the row exists even though nothing moved").isPresent();
        assertThat(level.orElseThrow().onHand()).isZero();

        // No movement was invented for a balance of zero — the ledger says nothing, correctly.
        assertThat(ledger.movementHistory(item.getId(), locationId)).isEmpty();

        // The point of all this: the refusal now names the real problem instead of a missing row.
        assertThatThrownBy(
                        () ->
                                reservations.reserve(
                                        List.of(new ReservationRequestLine(item.getId(), null, 1)),
                                        locationId,
                                        "test",
                                        null,
                                        null,
                                        actor))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .as("not STOCK_ROW_MISSING")
                                        .isEqualTo("INSUFFICIENT_STOCK"));
    }

    @Test
    @DisplayName("retail and wholesale are stored but change nothing about what an order charges")
    void quotedPricesAreReferenceOnly() {
        Item item =
                items.createWithOpeningStock(
                        "CHG-" + UUID.randomUUID(),
                        new ItemDetails(
                                "Quoted item",
                                "A description, which the form now collects",
                                null,
                                new BigDecimal("100.00"),
                                new BigDecimal("150.00"),
                                new BigDecimal("180.00"),
                                new BigDecimal("130.00"),
                                0),
                        new OpeningStock(locationId, 5),
                        actor);

        assertThat(item.getRetailPrice()).isEqualByComparingTo("180.00");
        assertThat(item.getWholesalePrice()).isEqualByComparingTo("130.00");
        assertThat(item.getDescription()).isNotBlank();

        // Sales still price from sellingPrice. Nothing about the order flow moved.
        assertThat(item.toRef().sellingPrice()).isEqualByComparingTo("150.00");
    }

    // ==============================================================================
    // Supplier pricing
    // ==============================================================================

    @Test
    @DisplayName("a purchase order takes the price of the supplier it is going to")
    void purchaseOrdersUseTheSuppliersOwnPrice() {
        Item item = newItem("500.00", new OpeningStock(locationId, 0));

        var cheap = newSupplier();
        var dear = newSupplier();
        supplierPrices.set(item.getId(), cheap, new BigDecimal("395.00"), "per case", actor);
        supplierPrices.set(item.getId(), dear, new BigDecimal("440.00"), null, actor);

        var fromCheap = orderFrom(cheap, item.getId());
        var fromDear = orderFrom(dear, item.getId());

        assertThat(fromCheap.getLines().getFirst().getUnitCost()).isEqualByComparingTo("395.00");
        assertThat(fromDear.getLines().getFirst().getUnitCost()).isEqualByComparingTo("440.00");
    }

    @Test
    @DisplayName("an unquoted item still falls back to the catalogue cost")
    void unquotedItemsFallBack() {
        Item item = newItem("500.00", new OpeningStock(locationId, 0));

        var order = orderFrom(newSupplier(), item.getId());

        assertThat(order.getLines().getFirst().getUnitCost())
                .as("nothing breaks for items that predate supplier pricing")
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("a typed price still wins over the supplier's quote")
    void anExplicitPriceOverridesEverything() {
        Item item = newItem("500.00", new OpeningStock(locationId, 0));
        var supplier = newSupplier();
        supplierPrices.set(item.getId(), supplier, new BigDecimal("395.00"), null, actor);

        var order =
                purchaseOrders.create(
                        supplier,
                        locationId,
                        null,
                        null,
                        List.of(
                                new PurchaseOrderService.LineRequest(
                                        item.getId(), 3, new BigDecimal("370.00"))),
                        actor);

        assertThat(order.getLines().getFirst().getUnitCost())
                .as("the negotiated price on the day beats any stored figure")
                .isEqualByComparingTo("370.00");
    }

    @Test
    @DisplayName("re-quoting replaces rather than duplicating")
    void settingAPriceTwiceUpdatesIt() {
        Item item = newItem("500.00", new OpeningStock(locationId, 0));
        var supplier = newSupplier();

        supplierPrices.set(item.getId(), supplier, new BigDecimal("395.00"), null, actor);
        supplierPrices.set(item.getId(), supplier, new BigDecimal("380.00"), "renegotiated", actor);

        var quotes = supplierPrices.forItem(item.getId());
        assertThat(quotes).hasSize(1);
        assertThat(quotes.getFirst().getPrice()).isEqualByComparingTo("380.00");
        assertThat(quotes.getFirst().getNote()).isEqualTo("renegotiated");
    }

    @Test
    @DisplayName("a deactivated supplier cannot be given a price")
    void deactivatedSuppliersAreRefused() {
        Item item = newItem("500.00", new OpeningStock(locationId, 0));
        var supplier = newSupplier();
        suppliers.setActive(supplier, false);

        assertThatThrownBy(
                        () ->
                                supplierPrices.set(
                                        item.getId(), supplier, new BigDecimal("100.00"), null, actor))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("SUPPLIER_INACTIVE"));
    }

    // ==============================================================================
    // Fixtures
    // ==============================================================================

    private com.democode.mlmsittu.commerce.internal.domain.PurchaseOrder orderFrom(
            UUID supplierId, UUID itemId) {
        return purchaseOrders.create(
                supplierId,
                locationId,
                null,
                null,
                List.of(new PurchaseOrderService.LineRequest(itemId, 2, null)),
                actor);
    }

    private UUID newSupplier() {
        return suppliers
                .create(
                        "CHGS-" + UUID.randomUUID(),
                        new SupplierService.SupplierDetails(
                                "Change request supplier", null, null, null, null, null, null))
                .getId();
    }

    private Item newItem(String cost, OpeningStock opening) {
        return items.createWithOpeningStock(
                "CHG-" + UUID.randomUUID(),
                new ItemDetails(
                        "Change request item",
                        "Description",
                        null,
                        new BigDecimal(cost),
                        new BigDecimal(cost),
                        null,
                        null,
                        0),
                opening,
                actor);
    }

    private UUID newUser() {
        AppUser user = new AppUser();
        user.setEmail("change-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Change request actor");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }
}
