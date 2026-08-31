package com.democode.mlmsittu.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService.OpeningStock;
import com.democode.mlmsittu.catalogue.internal.service.ItemService.ItemDetails;
import com.democode.mlmsittu.commerce.internal.domain.GoodsReceipt;
import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrder;
import com.democode.mlmsittu.commerce.internal.service.GoodsReceiptService;
import com.democode.mlmsittu.commerce.internal.service.PurchaseOrderService;
import com.democode.mlmsittu.commerce.internal.service.SupplierService;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.internal.location.LocationService;
import com.democode.mlmsittu.inventory.internal.reorder.ReorderAlert;
import com.democode.mlmsittu.inventory.internal.reorder.ReorderScanService;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.notify.NotificationSender;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Receiving, after the client split it in two (26 Aug 2026).
 *
 * <p>The behaviour worth protecting is the gap in the middle. Confirming a delivery arrived and
 * putting it into a store used to be one action; they are now two, and the value of the change is
 * entirely in what happens <em>between</em> them — a signed-for order whose stock has not moved.
 * Most of what follows is checking that gap really exists rather than being closed by a default
 * somewhere.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Receiving")
class ReceivingFlowTest {

    @Autowired private ItemProvisioningService items;
    @Autowired private SupplierService suppliers;
    @Autowired private PurchaseOrderService purchaseOrders;
    @Autowired private GoodsReceiptService goodsReceipts;
    @Autowired private StockLedger ledger;
    @Autowired private LocationService locations;
    @Autowired private ReorderScanService reorderScan;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoSpyBean private NotificationSender notifications;

    private UUID mainStore;
    private UUID actor;
    private String actorName;

    @BeforeEach
    void setUp() {
        mainStore = locations.defaultLocation().id();
        actorName = "Receiving Clerk " + UUID.randomUUID().toString().substring(0, 8);
        actor = newUser(actorName);
        Mockito.clearInvocations(notifications);
    }

    // ==============================================================================
    // Sending
    // ==============================================================================

    @Test
    @DisplayName("sending an order emails the supplier's contact, and records where it went")
    void sendingEmailsTheContact() {
        UUID supplier = newSupplier("orders@acme.test", "chandima@acme.test");
        Item item = newItem();
        PurchaseOrder order = orderFor(supplier, item.getId(), 10);

        PurchaseOrder sent = purchaseOrders.send(order.getId());

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(notifications)
                .sendEmail(to.capture(), Mockito.contains(sent.getPoNumber()), body.capture());

        assertThat(to.getValue())
                .as("the named contact, not the company switchboard")
                .isEqualTo("chandima@acme.test");
        assertThat(body.getValue())
                .as("the supplier can read the order without looking anything up")
                .contains(item.getName())
                .contains(item.getSku())
                .contains("10");
        assertThat(sent.getSentToEmail()).isEqualTo("chandima@acme.test");
        assertThat(sent.getStatus()).isEqualTo("sent");
    }

    @Test
    @DisplayName("a supplier with no email cannot be sent to at all")
    void sendingRefusesWhenThereIsNowhereToSend() {
        UUID supplier = newSupplier(null, null);
        PurchaseOrder order = orderFor(supplier, newItem().getId(), 3);

        assertThatThrownBy(() -> purchaseOrders.send(order.getId()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SUPPLIER_HAS_NO_EMAIL");

        // The point of refusing: the order must not look sent when it went nowhere.
        assertThat(purchaseOrders.get(order.getId()).getStatus()).isEqualTo("draft");
        Mockito.verify(notifications, Mockito.never())
                .sendEmail(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("a draft can be re-quoted for the same item it already has")
    void draftLinesCanBeReplacedInPlace() {
        Item item = newItem();
        PurchaseOrder order = orderFor(newSupplier("orders@acme.test", null), item.getId(), 4);

        PurchaseOrder edited =
                purchaseOrders.replaceLines(
                        order.getId(),
                        List.of(
                                new PurchaseOrderService.LineRequest(
                                        item.getId(), 10, new BigDecimal("95.00"))));

        // The interesting case, and the one that used to fail: the replacement keeps the same
        // item and the same line number, so the delete and the insert collide on
        // uq_po_line_item unless the removal is flushed first.
        assertThat(edited.getLines()).hasSize(1);
        assertThat(edited.getLines().get(0).getQuantityOrdered()).isEqualTo(10);
        assertThat(edited.getLines().get(0).getUnitCost()).isEqualByComparingTo("95.00");
    }

    // ==============================================================================
    // The gap: arrived, but not yet on a shelf
    // ==============================================================================

    @Test
    @DisplayName("goods cannot be put into a store until somebody confirms they arrived")
    void storingIsRefusedBeforeArrivalIsConfirmed() {
        PurchaseOrder order = sentOrder(newItem().getId(), 5);

        assertThatThrownBy(
                        () ->
                                goodsReceipts.storeFromOrder(
                                        order.getId(),
                                        mainStore,
                                        List.of(lineOf(order, 0, 5, null)),
                                        null,
                                        actor))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PURCHASE_ORDER_NOT_RECEIVABLE");
    }

    @Test
    @DisplayName("confirming arrival needs your own name, and forgives only the capitals")
    void arrivalNeedsTheRightName() {
        PurchaseOrder order = sentOrder(newItem().getId(), 5);

        assertThatThrownBy(
                        () -> purchaseOrders.confirmArrival(order.getId(), "Somebody Else", actor))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ATTESTED_NAME_MISMATCH");

        PurchaseOrder confirmed =
                purchaseOrders.confirmArrival(order.getId(), "  " + actorName.toUpperCase() + " ", actor);

        assertThat(confirmed.getStatus()).isEqualTo("arrived");
        assertThat(confirmed.getArrivedBy()).isEqualTo(actor);
        assertThat(confirmed.getArrivalAttestedName())
                .as("stored as the account spells it, not as it was typed")
                .isEqualTo(actorName);
    }

    @Test
    @DisplayName("confirming arrival moves no stock — that is the whole point of the step")
    void arrivalDoesNotTouchStock() {
        Item item = newItem();
        long before = onHand(item.getId(), mainStore);

        PurchaseOrder order = sentOrder(item.getId(), 7);
        purchaseOrders.confirmArrival(order.getId(), actorName, actor);

        assertThat(onHand(item.getId(), mainStore)).isEqualTo(before);
    }

    @Test
    @DisplayName("an order already confirmed cannot be confirmed again")
    void arrivalIsNotRepeatable() {
        PurchaseOrder order = arrivedOrder(newItem().getId(), 4);

        assertThatThrownBy(() -> purchaseOrders.confirmArrival(order.getId(), actorName, actor))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PURCHASE_ORDER_NOT_AWAITING_ARRIVAL");
    }

    @Test
    @DisplayName("a delivery that has been signed for cannot be cancelled")
    void arrivedOrdersCannotBeCancelled() {
        PurchaseOrder order = arrivedOrder(newItem().getId(), 4);

        assertThatThrownBy(() -> purchaseOrders.cancel(order.getId()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PURCHASE_ORDER_ALREADY_ARRIVED");
    }

    // ==============================================================================
    // Putting it away
    // ==============================================================================

    @Test
    @DisplayName("each line goes into the store it was given, not the order's")
    void linesGoIntoTheirOwnStore() {
        UUID annex = newStore();
        Item first = newItem();
        Item second = newItem();

        PurchaseOrder order =
                purchaseOrders.create(
                        newSupplier("orders@acme.test", null),
                        mainStore,
                        null,
                        null,
                        List.of(
                                new PurchaseOrderService.LineRequest(first.getId(), 6, null),
                                new PurchaseOrderService.LineRequest(second.getId(), 4, null)),
                        actor);
        purchaseOrders.send(order.getId());
        purchaseOrders.confirmArrival(order.getId(), actorName, actor);

        long firstBefore = onHand(first.getId(), mainStore);

        goodsReceipts.storeFromOrder(
                order.getId(),
                mainStore,
                List.of(lineOf(order, 0, 6, null), lineOf(order, 1, 4, annex)),
                null,
                actor);

        assertThat(onHand(first.getId(), mainStore)).isEqualTo(firstBefore + 6);
        assertThat(onHand(second.getId(), annex)).isEqualTo(4);
        assertThat(onHand(second.getId(), mainStore))
                .as("nothing leaked into the fallback store")
                .isZero();
    }

    @Test
    @DisplayName("a part delivery leaves the order open; the rest closes it")
    void partialThenComplete() {
        Item item = newItem();
        PurchaseOrder order = arrivedOrder(item.getId(), 10);
        long before = onHand(item.getId(), mainStore);

        goodsReceipts.storeFromOrder(
                order.getId(), mainStore, List.of(lineOf(order, 0, 4, null)), null, actor);

        assertThat(purchaseOrders.get(order.getId()).getStatus()).isEqualTo("partially_received");
        assertThat(onHand(item.getId(), mainStore)).isEqualTo(before + 4);

        goodsReceipts.storeFromOrder(
                order.getId(), mainStore, List.of(lineOf(order, 0, 6, null)), null, actor);

        PurchaseOrder closed = purchaseOrders.get(order.getId());
        assertThat(closed.getStatus()).isEqualTo("received");
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(onHand(item.getId(), mainStore)).isEqualTo(before + 10);
    }

    @Test
    @DisplayName("more than the order says is refused, with the numbers")
    void overReceiptIsRefused() {
        PurchaseOrder order = arrivedOrder(newItem().getId(), 3);

        assertThatThrownBy(
                        () ->
                                goodsReceipts.storeFromOrder(
                                        order.getId(),
                                        mainStore,
                                        List.of(lineOf(order, 0, 4, null)),
                                        null,
                                        actor))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "OVER_RECEIPT");
    }

    @Test
    @DisplayName("every movement points back at the receipt that caused it")
    void movementsAreTraceable() {
        Item item = newItem();
        PurchaseOrder order = arrivedOrder(item.getId(), 5);

        GoodsReceipt receipt =
                goodsReceipts.storeFromOrder(
                        order.getId(), mainStore, List.of(lineOf(order, 0, 5, null)), null, actor);

        assertThat(ledger.movementsFor("goods_receipt", receipt.getId()))
                .singleElement()
                .satisfies(
                        movement -> {
                            assertThat(movement.itemId()).isEqualTo(item.getId());
                            assertThat(movement.qtyDelta()).isEqualTo(5);
                        });
    }

    // ==============================================================================
    // Manual entry
    // ==============================================================================

    @Test
    @DisplayName("goods with no order behind them still raise stock, and still name a supplier")
    void manualEntryRaisesStock() {
        Item item = newItem();
        UUID supplier = newSupplier("orders@acme.test", null);
        long before = onHand(item.getId(), mainStore);

        GoodsReceipt receipt =
                goodsReceipts.storeManual(
                        supplier,
                        mainStore,
                        List.of(new GoodsReceiptService.ManualLineRequest(item.getId(), 12, null)),
                        "walk-in delivery",
                        actor);

        assertThat(receipt.getSource()).isEqualTo("manual");
        assertThat(receipt.getPurchaseOrderId()).isNull();
        assertThat(receipt.getSupplierId()).isEqualTo(supplier);
        assertThat(onHand(item.getId(), mainStore)).isEqualTo(before + 12);
        assertThat(ledger.movementsFor("goods_receipt", receipt.getId())).hasSize(1);
    }

    @Test
    @DisplayName("the same item into two stores is two lines; into one store twice, it is one")
    void manualLinesCombineByItemAndStore() {
        Item item = newItem();
        UUID annex = newStore();

        GoodsReceipt receipt =
                goodsReceipts.storeManual(
                        newSupplier("orders@acme.test", null),
                        mainStore,
                        List.of(
                                new GoodsReceiptService.ManualLineRequest(item.getId(), 3, null),
                                new GoodsReceiptService.ManualLineRequest(item.getId(), 2, null),
                                new GoodsReceiptService.ManualLineRequest(item.getId(), 5, annex)),
                        null,
                        actor);

        assertThat(receipt.getLines()).hasSize(2);
        assertThat(onHand(item.getId(), mainStore)).isEqualTo(5);
        assertThat(onHand(item.getId(), annex)).isEqualTo(5);
    }

    @Test
    @DisplayName("a deactivated supplier cannot deliver, even by hand")
    void manualEntryRefusesADeadSupplier() {
        UUID supplier = newSupplier("orders@acme.test", null);
        suppliers.setActive(supplier, false);
        Item item = newItem();

        assertThatThrownBy(
                        () ->
                                goodsReceipts.storeManual(
                                        supplier,
                                        mainStore,
                                        List.of(
                                                new GoodsReceiptService.ManualLineRequest(
                                                        item.getId(), 1, null)),
                                        null,
                                        actor))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an unknown store is refused rather than quietly becoming the default")
    void unknownStoreIsRefused() {
        Item item = newItem();

        assertThatThrownBy(
                        () ->
                                goodsReceipts.storeManual(
                                        newSupplier("orders@acme.test", null),
                                        UUID.randomUUID(),
                                        List.of(
                                                new GoodsReceiptService.ManualLineRequest(
                                                        item.getId(), 1, null)),
                                        null,
                                        actor))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "LOCATION_NOT_FOUND");
    }

    // ==============================================================================
    // Stores
    // ==============================================================================

    @Test
    @DisplayName("the default store cannot be deactivated out from under everything")
    void theDefaultStoreStays() {
        assertThatThrownBy(() -> locations.setActive(mainStore, false))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DEFAULT_STORE_REQUIRED");
    }

    // ==============================================================================
    // Reorder, judged on the item rather than on a shelf
    // ==============================================================================

    @Test
    @DisplayName("opening a new store does not make every item look short")
    void anEmptyStoreRaisesNothing() {
        // Comfortably above the threshold, all of it in the main store.
        Item item = newItem();
        receiveInto(item, 40, mainStore);
        reorderScan.scan();
        assertThat(openAlertFor(item)).as("stocked item, no alert").isEmpty();

        // Opening a store creates an empty position for nothing in particular. Under the old
        // per-store rule this alone raised an alert for every tracked item in the new store.
        UUID annex = newStore();
        receiveInto(newItem(), 1, annex);
        reorderScan.scan();

        assertThat(openAlertFor(item))
                .as("a new store must not make a well-stocked item look short")
                .isEmpty();
    }

    @Test
    @DisplayName("stock split across two stores is judged on the total, not on either half")
    void theThresholdSeesTheWholeItem() {
        Item item = newItem();
        UUID annex = newStore();

        // Reorder level is 5. Three in one store and four in the other is seven — plenty — but
        // both halves are under the threshold on their own.
        receiveInto(item, 3, mainStore);
        receiveInto(item, 4, annex);
        reorderScan.scan();

        assertThat(openAlertFor(item))
                .as("seven in total is above a threshold of five, wherever it sits")
                .isEmpty();
    }

    @Test
    @DisplayName("an item that really is short raises one alert, with no store attached")
    void agenuineShortageStillRaises() {
        Item item = newItem();
        UUID annex = newStore();
        receiveInto(item, 2, mainStore);
        receiveInto(item, 1, annex);
        reorderScan.scan();

        assertThat(openAlertFor(item))
                .singleElement()
                .satisfies(
                        alert -> {
                            assertThat(alert.getOnHandAtDetection())
                                    .as("the total, not one store's share")
                                    .isEqualTo(3);
                            assertThat(alert.getLocationId())
                                    .as("the shortage belongs to the item, not to a shelf")
                                    .isNull();
                        });

        // Replenish anywhere and it clears.
        receiveInto(item, 20, annex);
        reorderScan.scan();
        assertThat(openAlertFor(item)).isEmpty();
    }

    @Test
    @DisplayName("the movement says which order the stock came in on")
    void movementsCarryTheirDocument() {
        Item item = newItem();
        PurchaseOrder order = arrivedOrder(item.getId(), 5);

        GoodsReceipt receipt =
                goodsReceipts.storeFromOrder(
                        order.getId(), mainStore, List.of(lineOf(order, 0, 5, null)), null, actor);

        assertThat(ledger.movementsFor("goods_receipt", receipt.getId()))
                .singleElement()
                .satisfies(
                        movement ->
                                assertThat(movement.note())
                                        .as("readable without looking anything up")
                                        .contains(receipt.getReceiptNumber())
                                        .contains(order.getPoNumber())
                                        .contains("Receiving test supplier"));
    }

    @Test
    @DisplayName("a manual entry says so, and names the supplier")
    void manualMovementsSaySo() {
        Item item = newItem();
        GoodsReceipt receipt =
                goodsReceipts.storeManual(
                        newSupplier("orders@acme.test", null),
                        mainStore,
                        List.of(new GoodsReceiptService.ManualLineRequest(item.getId(), 4, null)),
                        null,
                        actor);

        assertThat(ledger.movementsFor("goods_receipt", receipt.getId()))
                .singleElement()
                .satisfies(
                        movement ->
                                assertThat(movement.note())
                                        .contains(receipt.getReceiptNumber())
                                        .contains("by hand"));
    }

    // ==============================================================================
    // Fixtures
    // ==============================================================================

    private GoodsReceiptService.ReceiptLineRequest lineOf(
            PurchaseOrder order, int index, int quantity, UUID locationId) {
        return new GoodsReceiptService.ReceiptLineRequest(
                purchaseOrders.get(order.getId()).getLines().get(index).getId(),
                quantity,
                locationId);
    }

    private PurchaseOrder orderFor(UUID supplierId, UUID itemId, int quantity) {
        return purchaseOrders.create(
                supplierId,
                mainStore,
                null,
                null,
                List.of(new PurchaseOrderService.LineRequest(itemId, quantity, null)),
                actor);
    }

    private PurchaseOrder sentOrder(UUID itemId, int quantity) {
        PurchaseOrder order = orderFor(newSupplier("orders@acme.test", null), itemId, quantity);
        return purchaseOrders.send(order.getId());
    }

    private PurchaseOrder arrivedOrder(UUID itemId, int quantity) {
        PurchaseOrder order = sentOrder(itemId, quantity);
        return purchaseOrders.confirmArrival(order.getId(), actorName, actor);
    }

    private List<ReorderAlert> openAlertFor(Item item) {
        return reorderScan.listAlerts(true).stream()
                .filter(alert -> alert.getItemId().equals(item.getId()))
                .toList();
    }

    /** Straight into a store by hand — the shortest route to a known quantity. */
    private void receiveInto(Item item, int quantity, UUID store) {
        goodsReceipts.storeManual(
                newSupplier("orders@acme.test", null),
                store,
                List.of(new GoodsReceiptService.ManualLineRequest(item.getId(), quantity, null)),
                null,
                actor);
    }

    private long onHand(UUID itemId, UUID locationId) {
        return ledger.levelOf(itemId, locationId).map(level -> (long) level.onHand()).orElse(0L);
    }

    private UUID newStore() {
        return locations
                .create(
                        "ANNEX-" + UUID.randomUUID().toString().substring(0, 8),
                        new LocationService.StoreDetails("Receiving annex", "Kandy"))
                .getId();
    }

    private UUID newSupplier(String companyEmail, String contactEmail) {
        return suppliers
                .create(
                        "RCV-" + UUID.randomUUID(),
                        new SupplierService.SupplierDetails(
                                "Receiving test supplier",
                                "Chandima",
                                companyEmail,
                                null,
                                null,
                                contactEmail,
                                null))
                .getId();
    }

    private Item newItem() {
        return items.createWithOpeningStock(
                "RCV-" + UUID.randomUUID(),
                new ItemDetails(
                        "Receiving test item",
                        "Description",
                        null,
                        new BigDecimal("100.00"),
                        new BigDecimal("150.00"),
                        null,
                        null,
                        5),
                new OpeningStock(mainStore, 0),
                actor);
    }

    private UUID newUser(String fullName) {
        AppUser user = new AppUser();
        user.setEmail("receiving-" + UUID.randomUUID() + "@test.local");
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }
}
