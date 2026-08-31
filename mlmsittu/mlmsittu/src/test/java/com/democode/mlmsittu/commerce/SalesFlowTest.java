package com.democode.mlmsittu.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.repo.ItemRepository;
import com.democode.mlmsittu.catalogue.internal.service.ItemSetService;
import com.democode.mlmsittu.catalogue.internal.service.ItemSetService.ComponentRequest;
import com.democode.mlmsittu.commerce.internal.domain.Customer;
import com.democode.mlmsittu.commerce.internal.domain.Payment;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrder;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrderStatus;
import com.democode.mlmsittu.commerce.internal.service.CustomerService;
import com.democode.mlmsittu.commerce.internal.service.FulfilmentService;
import com.democode.mlmsittu.commerce.internal.service.InvoiceService;
import com.democode.mlmsittu.commerce.internal.service.PaymentService;
import com.democode.mlmsittu.commerce.internal.service.SalesOrderService;
import com.democode.mlmsittu.commerce.internal.service.SalesOrderService.LineRequest;
import com.democode.mlmsittu.commerce.internal.service.SalesOrderService.OrderRequest;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.ReservationView;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockMovementView;
import com.democode.mlmsittu.inventory.api.StockPosting;
import com.democode.mlmsittu.inventory.api.StockReservations;
import com.democode.mlmsittu.inventory.api.StockView;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Gate 5 conditions.
 *
 * <p>These run against the service layer rather than HTTP, because everything Gate 5 asks about —
 * reserving, the duplicate-reference index, four-eyes verification, gap-free numbering — is decided
 * below the controller. The role gating that sits above it is declarative
 * ({@code @PreAuthorize}) and is exercised by hand through the UI, which is where a wrong role
 * annotation would actually bite.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Sales, payments and fulfilment")
class SalesFlowTest {

    @Autowired private CustomerService customers;
    @Autowired private SalesOrderService orders;
    @Autowired private PaymentService payments;
    @Autowired private FulfilmentService fulfilment;
    @Autowired private InvoiceService invoices;
    @Autowired private StockReservations reservations;
    @Autowired private StockLedger ledger;
    @Autowired private ItemSetService sets;
    @Autowired private ItemRepository items;
    @Autowired private AppUserRepository users;
    @Autowired private LocationDirectory locations;
    @Autowired private DocumentVault vault;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private UUID locationId;

    /** Whoever sells and records the money. */
    private UUID clerk;

    /** A second finance officer. Verification is theirs, never the recorder's. */
    private UUID officer;

    @BeforeEach
    void setUp() {
        locationId = locations.defaultLocation().id();
        clerk = newUser("sales-clerk");
        officer = newUser("finance-officer");
    }

    // ==============================================================================
    // Gate 5 · the full path
    // ==============================================================================

    @Test
    @DisplayName("Gate 5 · order, reserve, slip, verify, fulfil — and the stock is gone")
    void fullPathDecrementsStock() {
        UUID itemId = newItemWithStock(40, "250.00");
        UUID customerId = newCustomer();

        SalesOrder order =
                orders.create(
                        new OrderRequest(
                                customerId, locationId,
                                List.of(new LineRequest(itemId, null, 6, null)),
                                null, "full path", null),
                        clerk);

        // Reserved, not yet gone: the goods are still physically here.
        StockView afterOrder = levelOf(itemId);
        assertThat(afterOrder.onHand()).isEqualTo(40);
        assertThat(afterOrder.reserved()).isEqualTo(6);
        assertThat(order.statusValue()).isEqualTo(SalesOrderStatus.AWAITING_PAYMENT);

        Payment payment = recordPayment(order, "TXN-" + UUID.randomUUID(), order.getTotal());
        assertThat(orders.get(order.getId()).statusValue())
                .isEqualTo(SalesOrderStatus.PAYMENT_REVIEW);

        payments.verify(payment.getId(), officer);
        assertThat(orders.get(order.getId()).statusValue()).isEqualTo(SalesOrderStatus.PAID);

        var result = fulfilment.fulfil(order.getId(), officer);

        // P5-08 · both figures fall by the same amount.
        StockView afterFulfilment = levelOf(itemId);
        assertThat(afterFulfilment.onHand()).as("on_hand drops").isEqualTo(34);
        assertThat(afterFulfilment.reserved()).as("reserved drops with it").isZero();

        // ...and the ledger says why.
        List<StockMovementView> movements =
                ledger.movementsFor("sales_order", order.getId());
        assertThat(movements).hasSize(1);
        assertThat(movements.getFirst().qtyDelta()).isEqualTo(-6);

        // The projection still agrees with the ledger it is derived from.
        assertThat(ledger.ledgerTotalFor(itemId, locationId)).isEqualTo(34);

        assertThat(result.order().statusValue()).isEqualTo(SalesOrderStatus.FULFILLED);
        assertThat(result.invoice().getInvoiceNumber()).startsWith("INV-");
        assertThat(reservations.get(order.getReservationId()).status()).isEqualTo("consumed");
    }

    @Test
    @DisplayName("P5-02 · an order for an item and a set reserves every component")
    void setLinesExpandIntoComponents() {
        UUID loose = newItemWithStock(100, "50.00");
        UUID partA = newItemWithStock(100, "10.00");
        UUID partB = newItemWithStock(100, "10.00");

        UUID setId =
                sets.create(
                                "SET-" + UUID.randomUUID(),
                                "Sales fixture pack",
                                null,
                                new BigDecimal("300.00"),
                                List.of(new ComponentRequest(partA, 2), new ComponentRequest(partB, 3)))
                        .getId();

        SalesOrder order =
                orders.create(
                        new OrderRequest(
                                newCustomer(),
                                locationId,
                                List.of(
                                        new LineRequest(loose, null, 4, null),
                                        new LineRequest(null, setId, 5, null)),
                                null, null, null),
                        clerk);

        // Total is line prices, not component prices: 4 x 50 + 5 x 300.
        assertThat(order.getSubtotal()).isEqualByComparingTo("1700.00");
        assertThat(order.getTotal()).isEqualByComparingTo("1700.00");

        // The reservation holds components, because that is what leaves the shelf.
        ReservationView held = reservations.get(order.getReservationId());
        assertThat(held.lines()).hasSize(3);
        assertThat(levelOf(loose).reserved()).isEqualTo(4);
        assertThat(levelOf(partA).reserved()).as("2 per set x 5 sets").isEqualTo(10);
        assertThat(levelOf(partB).reserved()).as("3 per set x 5 sets").isEqualTo(15);
    }

    @Test
    @DisplayName("a discount comes off the total without touching the lines")
    void discountAppliesToTheTotal() {
        UUID itemId = newItemWithStock(10, "100.00");

        SalesOrder order =
                orders.create(
                        new OrderRequest(
                                newCustomer(), locationId,
                                List.of(new LineRequest(itemId, null, 3, null)),
                                new BigDecimal("50.00"), null, null),
                        clerk);

        assertThat(order.getSubtotal()).isEqualByComparingTo("300.00");
        assertThat(order.getTotal()).isEqualByComparingTo("250.00");
        assertThat(order.getLines().getFirst().getLineTotal()).isEqualByComparingTo("300.00");
    }

    // ==============================================================================
    // P5-03 · idempotency
    // ==============================================================================

    @Test
    @DisplayName("Gate 5 · the same key returns the first order and creates no second")
    void idempotentRetryReturnsTheOriginal() {
        UUID itemId = newItemWithStock(30, "80.00");
        UUID customerId = newCustomer();
        String key = "abc-123-" + UUID.randomUUID();

        OrderRequest request =
                new OrderRequest(
                        customerId, locationId,
                        List.of(new LineRequest(itemId, null, 5, null)),
                        null, null, key);

        SalesOrder first = orders.create(request, clerk);
        SalesOrder replay = orders.create(request, clerk);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getOrderNumber()).isEqualTo(first.getOrderNumber());

        // The decisive check: a second order would have reserved a second five units.
        assertThat(levelOf(itemId).reserved()).as("stock held once, not twice").isEqualTo(5);
        assertThat(orders.list(customerId, null)).hasSize(1);

        // A new key is a new order, not a retry.
        SalesOrder second =
                orders.create(
                        new OrderRequest(
                                customerId, locationId,
                                List.of(new LineRequest(itemId, null, 5, null)),
                                null, null, "different-" + UUID.randomUUID()),
                        clerk);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(levelOf(itemId).reserved()).isEqualTo(10);
    }

    @Test
    @DisplayName("P5-03 · the same key with a different order is refused, not silently accepted")
    void reusingAKeyForADifferentOrderIsRejected() {
        UUID itemId = newItemWithStock(30, "80.00");
        UUID customerId = newCustomer();
        String key = "reused-" + UUID.randomUUID();

        orders.create(
                new OrderRequest(
                        customerId, locationId,
                        List.of(new LineRequest(itemId, null, 5, null)),
                        null, null, key),
                clerk);

        assertThatThrownBy(
                        () ->
                                orders.create(
                                        new OrderRequest(
                                                customerId, locationId,
                                                List.of(new LineRequest(itemId, null, 9, null)),
                                                null, null, key),
                                        clerk))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));

        assertThat(levelOf(itemId).reserved()).as("nothing extra was held").isEqualTo(5);
    }

    @Test
    @DisplayName("P5-03 · a key belongs to the person who sent it")
    void keysAreScopedToTheSender() {
        UUID itemId = newItemWithStock(30, "80.00");
        String sharedKey = "shared-" + UUID.randomUUID();

        SalesOrder mine =
                orders.create(
                        new OrderRequest(
                                newCustomer(), locationId,
                                List.of(new LineRequest(itemId, null, 2, null)),
                                null, null, sharedKey),
                        clerk);

        SalesOrder theirs =
                orders.create(
                        new OrderRequest(
                                newCustomer(), locationId,
                                List.of(new LineRequest(itemId, null, 2, null)),
                                null, null, sharedKey),
                        officer);

        assertThat(theirs.getId())
                .as("one operator's key must not hand back another operator's order")
                .isNotEqualTo(mine.getId());
    }

    // ==============================================================================
    // P5-05, P5-06, P5-07 · payments
    // ==============================================================================

    @Test
    @DisplayName("Gate 5 · one bank reference cannot pay for two orders")
    void duplicateBankReferenceIsBlocked() {
        UUID itemId = newItemWithStock(50, "100.00");
        SalesOrder first = simpleOrder(itemId, 2);
        SalesOrder second = simpleOrder(itemId, 3);

        String reference = "TXN99887-" + UUID.randomUUID();
        recordPayment(first, reference, first.getTotal());

        assertThatThrownBy(() -> recordPayment(second, reference, second.getTotal()))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown -> {
                            ApiException failure = (ApiException) thrown;
                            assertThat(failure.getCode()).isEqualTo("DUPLICATE_BANK_REFERENCE");

                            // The error must not say which order got there first — that would turn
                            // a valid slip into a way of enumerating other people's orders.
                            assertThat(failure.getMessage())
                                    .doesNotContain(first.getOrderNumber())
                                    .doesNotContain(first.getId().toString());
                            assertThat(failure.getProperties()).isEmpty();
                        });

        // And the refused attempt left the second order exactly as it was.
        assertThat(orders.get(second.getId()).statusValue())
                .isEqualTo(SalesOrderStatus.AWAITING_PAYMENT);
        assertThat(payments.forOrder(second.getId())).isEmpty();
    }

    @Test
    @DisplayName("Gate 5 · whoever recorded a payment cannot be the one to verify it")
    void recorderCannotVerifyTheirOwnPayment() {
        UUID itemId = newItemWithStock(20, "100.00");
        SalesOrder order = simpleOrder(itemId, 2);
        Payment payment = recordPayment(order, "TXN-" + UUID.randomUUID(), order.getTotal());

        assertThatThrownBy(() -> payments.verify(payment.getId(), clerk))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("SELF_VERIFICATION_FORBIDDEN"));

        // The same is true of rejecting: a decision either way needs a second pair of eyes.
        assertThatThrownBy(() -> payments.reject(payment.getId(), clerk, "changed my mind"))
                .isInstanceOf(ApiException.class);

        // Somebody else can.
        Payment verified = payments.verify(payment.getId(), officer);
        assertThat(verified.getStatus()).isEqualTo(Payment.VERIFIED);
        assertThat(verified.getVerifiedBy()).isEqualTo(officer);
    }

    @Test
    @DisplayName("a payment is decided once")
    void aDecidedPaymentCannotBeDecidedAgain() {
        UUID itemId = newItemWithStock(20, "100.00");
        SalesOrder order = simpleOrder(itemId, 2);
        Payment payment = recordPayment(order, "TXN-" + UUID.randomUUID(), order.getTotal());

        payments.verify(payment.getId(), officer);

        assertThatThrownBy(() -> payments.verify(payment.getId(), officer))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("PAYMENT_ALREADY_DECIDED"));
    }

    // ==============================================================================
    // P5-09 · rejection
    // ==============================================================================

    @Test
    @DisplayName("Gate 5 · rejecting a payment gives the stock back and lets the buyer try again")
    void rejectionReleasesTheReservation() {
        UUID itemId = newItemWithStock(20, "100.00");
        SalesOrder order = simpleOrder(itemId, 8);

        assertThat(levelOf(itemId).reserved()).isEqualTo(8);

        Payment payment = recordPayment(order, "TXN-" + UUID.randomUUID(), order.getTotal());
        payments.reject(payment.getId(), officer, "slip does not match the amount");

        SalesOrder rejected = orders.get(order.getId());
        assertThat(rejected.statusValue()).isEqualTo(SalesOrderStatus.PAYMENT_REJECTED);
        assertThat(rejected.getReservationId()).as("nothing is being held").isNull();

        StockView recovered = levelOf(itemId);
        assertThat(recovered.reserved()).isZero();
        assertThat(recovered.onHand()).as("the goods never moved").isEqualTo(20);
        assertThat(recovered.available()).isEqualTo(20);

        // The buyer resubmits. The order takes its stock again.
        Payment second = recordPayment(rejected, "TXN-" + UUID.randomUUID(), order.getTotal());
        SalesOrder resubmitted = orders.get(order.getId());

        assertThat(resubmitted.statusValue()).isEqualTo(SalesOrderStatus.PAYMENT_REVIEW);
        assertThat(resubmitted.getReservationId()).isNotNull();
        assertThat(levelOf(itemId).reserved()).isEqualTo(8);

        payments.verify(second.getId(), officer);
        fulfilment.fulfil(order.getId(), officer);
        assertThat(levelOf(itemId).onHand()).isEqualTo(12);
    }

    @Test
    @DisplayName("cancelling an unfulfilled order releases its stock")
    void cancellationReleasesStock() {
        UUID itemId = newItemWithStock(20, "100.00");
        SalesOrder order = simpleOrder(itemId, 7);

        orders.cancel(order.getId(), "customer changed their mind", clerk);

        assertThat(levelOf(itemId).reserved()).isZero();
        assertThat(levelOf(itemId).onHand()).isEqualTo(20);
        assertThat(orders.get(order.getId()).statusValue()).isEqualTo(SalesOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("goods are not released until the money has been verified")
    void fulfilmentRequiresAVerifiedPayment() {
        UUID itemId = newItemWithStock(20, "100.00");
        SalesOrder order = simpleOrder(itemId, 2);

        assertThatThrownBy(() -> fulfilment.fulfil(order.getId(), officer))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("ORDER_NOT_PAID"));

        assertThat(levelOf(itemId).onHand()).as("nothing left the building").isEqualTo(20);
    }

    // ==============================================================================
    // P5-10 · invoice numbering
    // ==============================================================================

    @Test
    @DisplayName("Gate 5 · invoice numbers run consecutively with no gaps")
    void invoiceNumbersAreSequential() {
        UUID itemId = newItemWithStock(60, "100.00");

        long first = fulfilOne(itemId).getSequenceNo();
        long second = fulfilOne(itemId).getSequenceNo();
        long third = fulfilOne(itemId).getSequenceNo();

        assertThat(second).isEqualTo(first + 1);
        assertThat(third).isEqualTo(second + 1);

        // Nothing is missing between the lowest and highest number ever issued, either.
        var all = invoices.list();
        assertThat(all.getLast().getSequenceNo() - all.getFirst().getSequenceNo() + 1)
                .as("count matches the span, so there are no holes")
                .isEqualTo(all.size());
    }

    @Test
    @DisplayName("Gate 5 · a failure mid-generation gives the number back rather than burning it")
    void aRolledBackInvoiceDoesNotLeaveAGap() {
        UUID itemId = newItemWithStock(20, "100.00");
        SalesOrder order = paidOrder(itemId, 2);

        long before = counterValue();

        // Stands in for the app being killed between taking a number and committing the invoice.
        // A sequence would have burned the number here; the counter row rolls back with everything
        // else.
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        status -> {
                                            invoices.issueFor(order, officer);
                                            throw new IllegalStateException("simulated crash");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(counterValue()).as("the number was not consumed").isEqualTo(before);

        // The next invoice takes the number the failed attempt was going to use.
        var issued = fulfilment.fulfil(order.getId(), officer).invoice();
        assertThat(issued.getSequenceNo()).isEqualTo(before);
    }

    @Test
    @DisplayName("P5-10 · one order cannot be invoiced twice")
    void anOrderGetsExactlyOneInvoice() {
        UUID itemId = newItemWithStock(20, "100.00");
        SalesOrder order = paidOrder(itemId, 2);

        fulfilment.fulfil(order.getId(), officer);

        // A second fulfilment is refused on the order's status, before numbering is even reached.
        assertThatThrownBy(() -> fulfilment.fulfil(order.getId(), officer))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("ORDER_NOT_PAID"));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM invoice WHERE sales_order_id = ?",
                        Integer.class,
                        order.getId()))
                .isEqualTo(1);
    }

    // ==============================================================================
    // Fixtures
    // ==============================================================================

    private SalesOrder simpleOrder(UUID itemId, int quantity) {
        return orders.create(
                new OrderRequest(
                        newCustomer(), locationId,
                        List.of(new LineRequest(itemId, null, quantity, null)),
                        null, null, null),
                clerk);
    }

    private SalesOrder paidOrder(UUID itemId, int quantity) {
        SalesOrder order = simpleOrder(itemId, quantity);
        Payment payment = recordPayment(order, "TXN-" + UUID.randomUUID(), order.getTotal());
        payments.verify(payment.getId(), officer);
        return orders.get(order.getId());
    }

    private com.democode.mlmsittu.commerce.internal.domain.Invoice fulfilOne(UUID itemId) {
        SalesOrder order = paidOrder(itemId, 1);
        return fulfilment.fulfil(order.getId(), officer).invoice();
    }

    private Payment recordPayment(SalesOrder order, String bankRef, BigDecimal amount) {
        var slip = vault.store(smallJpeg(), "image/jpeg", "bank_slip", clerk);
        return payments.record(
                new PaymentService.PaymentEntry(
                        order.getId(), amount, bankRef, LocalDate.now(), slip.id()),
                clerk);
    }

    private long counterValue() {
        Long value =
                jdbc.queryForObject(
                        "SELECT next_value FROM document_counter WHERE name = 'invoice'",
                        Long.class);
        return value == null ? 0L : value;
    }

    private StockView levelOf(UUID itemId) {
        return ledger.levelOf(itemId, locationId).orElseThrow();
    }

    private UUID newCustomer() {
        Customer customer =
                customers.create(
                        "CUS-" + UUID.randomUUID(),
                        new CustomerService.CustomerDetails(
                                "Sales fixture buyer", null, null, null, "Colombo", null, null),
                        clerk);
        return customer.getId();
    }

    private UUID newItemWithStock(int quantity, String sellingPrice) {
        Item item = new Item();
        item.setSku("SALE-" + UUID.randomUUID());
        item.setName("Sales fixture");
        item.setUnitCost(BigDecimal.ONE);
        item.setSellingPrice(new BigDecimal(sellingPrice));
        item.setReorderLevel(0);
        item.setActive(true);
        UUID itemId = items.saveAndFlush(item).getId();

        ledger.post(StockPosting.openingBalance(itemId, locationId, quantity, clerk));
        return itemId;
    }

    private UUID newUser(String prefix) {
        AppUser user = new AppUser();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Sales fixture actor");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }

    private static byte[] smallJpeg() {
        try {
            BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(Color.DARK_GRAY);
            graphics.fillRect(0, 0, 24, 24);
            graphics.dispose();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
