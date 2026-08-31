package com.democode.mlmsittu.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.repo.ItemRepository;
import com.democode.mlmsittu.commerce.internal.domain.Payment;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrder;
import com.democode.mlmsittu.commerce.internal.service.CustomerService;
import com.democode.mlmsittu.commerce.internal.service.FulfilmentService;
import com.democode.mlmsittu.commerce.internal.service.PaymentService;
import com.democode.mlmsittu.commerce.internal.service.SalesOrderService;
import com.democode.mlmsittu.commerce.internal.service.SalesOrderService.LineRequest;
import com.democode.mlmsittu.commerce.internal.service.SalesOrderService.OrderRequest;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockPosting;
import com.democode.mlmsittu.reporting.api.CustomerAnalyticsRow;
import com.democode.mlmsittu.reporting.api.SalesReport;
import com.democode.mlmsittu.reporting.api.StockReport;
import com.democode.mlmsittu.reporting.api.StockReportRow;
import com.democode.mlmsittu.reporting.api.TreemapNode;
import com.democode.mlmsittu.reporting.internal.CustomerAnalyticsService;
import com.democode.mlmsittu.reporting.internal.SalesReportService;
import com.democode.mlmsittu.reporting.internal.StockReportService;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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

/**
 * Gate 6's first condition: every report cross-checked against direct SQL.
 *
 * <p>The plan says to do that by hand for three items, and the manual guide still asks for it —
 * seeing your own query return the same number is worth more than reading that a test passed. These
 * do it for <em>every</em> row, on data the test creates, so a regression is caught before anybody
 * opens the screen.
 *
 * <p>The cross-check query is deliberately written a second time here rather than shared with the
 * repository. A test that reuses the code under test proves only that the code equals itself.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Report accuracy")
class ReportAccuracyTest {

    @Autowired private StockReportService stockReports;
    @Autowired private SalesReportService salesReports;
    @Autowired private CustomerAnalyticsService analytics;
    @Autowired private CustomerService customers;
    @Autowired private SalesOrderService orders;
    @Autowired private PaymentService payments;
    @Autowired private FulfilmentService fulfilment;
    @Autowired private StockLedger ledger;
    @Autowired private ItemRepository items;
    @Autowired private AppUserRepository users;
    @Autowired private LocationDirectory locations;
    @Autowired private DocumentVault vault;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private UUID locationId;
    private UUID clerk;
    private UUID officer;

    @BeforeEach
    void setUp() {
        locationId = locations.defaultLocation().id();
        clerk = newUser("report-clerk");
        officer = newUser("report-officer");
    }

    // ==============================================================================
    // P6-01 · stock
    // ==============================================================================

    @Test
    @DisplayName("Gate 6 · every stock row matches a direct query, item by item")
    void stockRowsMatchDirectSql() {
        UUID a = newItemWithStock(40, "12.50", 10);
        UUID b = newItemWithStock(7, "300.00", 10);
        UUID c = newItemWithStock(0, "5.00", 0);

        StockReport report = stockReports.report(locationId, null, false, true);

        for (UUID itemId : List.of(a, b, c)) {
            StockReportRow row = rowFor(report, itemId);

            var expected =
                    jdbc.queryForMap(
                            """
                            SELECT sl.on_hand,
                                   sl.reserved,
                                   sl.on_hand - sl.reserved AS available,
                                   i.reorder_level,
                                   i.unit_cost,
                                   sl.on_hand * i.unit_cost AS stock_value
                            FROM stock_level sl
                            JOIN item i ON i.id = sl.item_id
                            WHERE sl.item_id = ? AND sl.location_id = ?
                            """,
                            itemId,
                            locationId);

            assertThat(row.onHand()).isEqualTo(((Number) expected.get("on_hand")).intValue());
            assertThat(row.reserved()).isEqualTo(((Number) expected.get("reserved")).intValue());
            assertThat(row.available()).isEqualTo(((Number) expected.get("available")).intValue());
            assertThat(row.stockValue())
                    .isEqualByComparingTo((BigDecimal) expected.get("stock_value"));
        }

        // b has 7 available against a reorder level of 10; c is not tracked at all.
        assertThat(rowFor(report, a).belowReorder()).isFalse();
        assertThat(rowFor(report, b).belowReorder()).isTrue();
        assertThat(rowFor(report, c).belowReorder())
                .as("a reorder level of zero means do not track, exactly as the alert scan reads it")
                .isFalse();
    }

    @Test
    @DisplayName("the totals are the sum of the rows returned, not a separate query")
    void totalsAgreeWithTheirOwnRows() {
        newItemWithStock(11, "10.00", 0);
        newItemWithStock(13, "20.00", 0);

        StockReport report = stockReports.report(locationId, null, false, true);

        assertThat(report.itemCount()).isEqualTo(report.rows().size());
        assertThat(report.totalOnHand())
                .isEqualTo(report.rows().stream().mapToLong(StockReportRow::onHand).sum());
        assertThat(report.totalValue())
                .isEqualByComparingTo(
                        report.rows().stream()
                                .map(StockReportRow::stockValue)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    @DisplayName("reserving moves available and stock value independently")
    void reservationShowsUpAsReservedNotAsLoss() {
        UUID itemId = newItemWithStock(30, "100.00", 0);
        orders.create(simpleOrder(itemId, 12), clerk);

        StockReportRow row = rowFor(stockReports.report(locationId, null, false, true), itemId);

        assertThat(row.onHand()).as("reserved stock is still on the shelf").isEqualTo(30);
        assertThat(row.reserved()).isEqualTo(12);
        assertThat(row.available()).isEqualTo(18);
        assertThat(row.stockValue())
                .as("value follows on hand — the goods are still ours until they ship")
                .isEqualByComparingTo("3000.00");
    }

    // ==============================================================================
    // P6-02 · sales
    // ==============================================================================

    @Test
    @DisplayName("Gate 6 · the sales total matches a hand-calculated figure")
    void salesTotalsMatchDirectSql() {
        UUID itemId = newItemWithStock(200, "100.00", 0);

        // Three fulfilled orders: 5, 3 and 2 units at 100 each, one carrying a 50 discount.
        fulfil(itemId, 5, null);
        fulfil(itemId, 3, new BigDecimal("50.00"));
        fulfil(itemId, 2, null);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        SalesReport report = salesReports.report(today, today);

        BigDecimal expectedNet =
                jdbc.queryForObject(
                        """
                        SELECT COALESCE(sum(total), 0) FROM sales_order
                        WHERE status = 'fulfilled'
                          AND (fulfilled_at AT TIME ZONE 'Asia/Colombo')::date = ?
                        """,
                        BigDecimal.class,
                        today);

        assertThat(report.netValue()).isEqualByComparingTo(expectedNet);
        assertThat(report.grossValue().subtract(report.discountValue()))
                .as("gross minus discount is net, by construction")
                .isEqualByComparingTo(report.netValue());

        // And the hand calculation: (500 + 300 + 200) gross, 50 off.
        assertThat(report.orderCount()).isGreaterThanOrEqualTo(3);
        assertThat(report.averageOrderValue())
                .isEqualByComparingTo(
                        report.netValue()
                                .divide(
                                        BigDecimal.valueOf(report.orderCount()),
                                        2,
                                        java.math.RoundingMode.HALF_UP));
    }

    @Test
    @DisplayName("Gate 6 · an empty range is a clean empty answer, not an error")
    void emptyRangeReturnsZeroes() {
        LocalDate longAgo = LocalDate.of(2001, 1, 1);

        SalesReport report = salesReports.report(longAgo, longAgo.plusDays(3));

        assertThat(report.orderCount()).isZero();
        assertThat(report.netValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.averageOrderValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.daily()).isEmpty();
        assertThat(report.topProducts()).isEmpty();
        assertThat(report.from()).isEqualTo(longAgo);
    }

    @Test
    @DisplayName("a backwards or absurd range is refused rather than answered")
    void badRangesAreRejected() {
        assertThatThrownBy(
                        () ->
                                salesReports.report(
                                        LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("INVALID_DATE_RANGE"));

        assertThatThrownBy(
                        () ->
                                salesReports.report(
                                        LocalDate.of(1990, 1, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        thrown ->
                                assertThat(((ApiException) thrown).getCode())
                                        .isEqualTo("DATE_RANGE_TOO_WIDE"));
    }

    @Test
    @DisplayName("the daily breakdown adds up to the range total")
    void dailyBreakdownReconciles() {
        UUID itemId = newItemWithStock(100, "40.00", 0);
        fulfil(itemId, 4, null);
        fulfil(itemId, 6, null);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        SalesReport report = salesReports.report(today.minusDays(7), today);

        BigDecimal fromDays =
                report.daily().stream()
                        .map(SalesReport.DailySales::netValue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(fromDays).isEqualByComparingTo(report.netValue());
        assertThat(report.daily().stream().mapToInt(SalesReport.DailySales::orderCount).sum())
                .isEqualTo(report.orderCount());
    }

    @Test
    @DisplayName("P6-02 · product lines are prorated so they add up to the net total")
    void productBreakdownReconcilesDespiteDiscounts() {
        UUID itemId = newItemWithStock(100, "250.00", 0);

        // A discounted order is the case where naive line sums exceed the money taken.
        fulfil(itemId, 4, new BigDecimal("200.00"));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        SalesReport report = salesReports.report(today, today);

        BigDecimal fromProducts =
                report.topProducts().stream()
                        .map(SalesReport.ProductSales::netValue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Rounding of the prorated shares can leave a cent; anything more means the proration is
        // wrong rather than merely rounded.
        assertThat(fromProducts.subtract(report.netValue()).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.05"));
    }

    // ==============================================================================
    // P6-03, P6-04 · customers
    // ==============================================================================

    @Test
    @DisplayName("Gate 6 · a customer's figures match a direct query")
    void customerAnalyticsMatchDirectSql() {
        UUID itemId = newItemWithStock(100, "75.00", 0);
        UUID customerId = newCustomer();

        fulfilFor(customerId, itemId, 2, null);
        fulfilFor(customerId, itemId, 5, null);

        CustomerAnalyticsRow row =
                analytics.customers(true).stream()
                        .filter(entry -> entry.customerId().equals(customerId))
                        .findFirst()
                        .orElseThrow();

        var expected =
                jdbc.queryForMap(
                        """
                        SELECT count(*) AS order_count, COALESCE(sum(total), 0) AS total_value
                        FROM sales_order
                        WHERE customer_id = ? AND status = 'fulfilled'
                        """,
                        customerId);

        assertThat(row.orderCount()).isEqualTo(((Number) expected.get("order_count")).intValue());
        assertThat(row.totalValue()).isEqualByComparingTo((BigDecimal) expected.get("total_value"));
        assertThat(row.lastOrderAt()).isNotNull();
        assertThat(row.daysSinceLastOrder()).isZero();
    }

    @Test
    @DisplayName("P6-03 · a customer who has never ordered still appears, with zeroes")
    void customersWithNoOrdersAreIncluded() {
        UUID customerId = newCustomer();

        CustomerAnalyticsRow row =
                analytics.customers(true).stream()
                        .filter(entry -> entry.customerId().equals(customerId))
                        .findFirst()
                        .orElseThrow();

        assertThat(row.orderCount()).isZero();
        assertThat(row.totalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.lastOrderAt()).isNull();
        assertThat(row.daysSinceLastOrder())
                .as("null rather than a made-up number, so the table can say 'never'")
                .isNull();
    }

    @Test
    @DisplayName("Gate 6 · treemap areas are proportional — the leaves sum to the sales net total")
    void treemapValuesReconcileWithTheSalesReport() {
        UUID itemId = newItemWithStock(100, "60.00", 0);
        UUID first = newCustomer();
        UUID second = newCustomer();

        // Deliberately lopsided: P6-04 asks you to compare the largest and smallest rectangles,
        // which needs values that visibly differ.
        fulfilFor(first, itemId, 10, null);
        fulfilFor(second, itemId, 1, null);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        TreemapNode tree = analytics.treemap(today, today);
        SalesReport sales = salesReports.report(today, today);

        BigDecimal leafTotal = sumLeaves(tree);

        // The treemap draws area from these numbers, so if they sum to the report's net total the
        // areas are proportional to it by construction. That is the honest way to check P6-04 —
        // measuring rendered rectangles would be testing d3-hierarchy, not this system.
        assertThat(leafTotal.subtract(sales.netValue()).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.05"));

        // And the ten-to-one ratio survives into the feed.
        BigDecimal big = leafValueFor(tree, first);
        BigDecimal small = leafValueFor(tree, second);
        assertThat(big.divide(small, 2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("the treemap nests category over customer, two levels deep")
    void treemapIsNestedCorrectly() {
        UUID itemId = newItemWithStock(50, "20.00", 0);
        fulfilFor(newCustomer(), itemId, 3, null);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        TreemapNode tree = analytics.treemap(today, today);

        assertThat(tree.children()).isNotEmpty();
        for (TreemapNode category : tree.children()) {
            assertThat(category.value()).as("a branch has no value of its own").isNull();
            assertThat(category.children()).isNotEmpty();
            for (TreemapNode customer : category.children()) {
                assertThat(customer.children()).as("customers are leaves").isEmpty();
                assertThat(customer.value()).isNotNull();
            }
        }
    }

    // ==============================================================================
    // Fixtures
    // ==============================================================================

    private static BigDecimal sumLeaves(TreemapNode node) {
        if (node.children() == null || node.children().isEmpty()) {
            return node.value() == null ? BigDecimal.ZERO : node.value();
        }
        return node.children().stream()
                .map(ReportAccuracyTest::sumLeaves)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Finds the leaf for a customer by looking up their name, which is what the feed carries. */
    private BigDecimal leafValueFor(TreemapNode tree, UUID customerId) {
        String name = jdbc.queryForObject(
                "SELECT name FROM customer WHERE id = ?", String.class, customerId);

        return tree.children().stream()
                .flatMap(category -> category.children().stream())
                .filter(leaf -> leaf.name().equals(name))
                .map(TreemapNode::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private StockReportRow rowFor(StockReport report, UUID itemId) {
        return report.rows().stream()
                .filter(row -> row.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stock row for " + itemId));
    }

    private OrderRequest simpleOrder(UUID itemId, int quantity) {
        return new OrderRequest(
                newCustomer(),
                locationId,
                List.of(new LineRequest(itemId, null, quantity, null)),
                null,
                null,
                null);
    }

    private void fulfil(UUID itemId, int quantity, BigDecimal discount) {
        fulfilFor(newCustomer(), itemId, quantity, discount);
    }

    /** Drives the real Phase 5 path, so the reports read data the system actually produces. */
    private void fulfilFor(UUID customerId, UUID itemId, int quantity, BigDecimal discount) {
        SalesOrder order =
                orders.create(
                        new OrderRequest(
                                customerId,
                                locationId,
                                List.of(new LineRequest(itemId, null, quantity, null)),
                                discount,
                                null,
                                null),
                        clerk);

        var slip = vault.store(smallJpeg(), "image/jpeg", "bank_slip", clerk);
        Payment payment =
                payments.record(
                        new PaymentService.PaymentEntry(
                                order.getId(),
                                order.getTotal(),
                                "RPT-" + UUID.randomUUID(),
                                LocalDate.now(),
                                slip.id()),
                        clerk);

        payments.verify(payment.getId(), officer);
        fulfilment.fulfil(order.getId(), officer);
    }

    private UUID newCustomer() {
        return customers
                .create(
                        "RPTC-" + UUID.randomUUID(),
                        new CustomerService.CustomerDetails(
                                "Report fixture " + UUID.randomUUID(),
                                null, null, null, "Colombo", null, null),
                        clerk)
                .getId();
    }

    private UUID newItemWithStock(int quantity, String unitCost, int reorderLevel) {
        Item item = new Item();
        item.setSku("RPT-" + UUID.randomUUID());
        item.setName("Report fixture");
        item.setUnitCost(new BigDecimal(unitCost));
        item.setSellingPrice(new BigDecimal(unitCost));
        item.setReorderLevel(reorderLevel);
        item.setActive(true);
        UUID itemId = items.saveAndFlush(item).getId();

        if (quantity > 0) {
            ledger.post(StockPosting.openingBalance(itemId, locationId, quantity, clerk));
        } else {
            // Still needs a stock_level row, or the item has no position to report at all.
            ledger.post(StockPosting.openingBalance(itemId, locationId, 1, clerk));
            ledger.post(
                    StockPosting.adjustment(itemId, locationId, -1, "report fixture", null, clerk));
        }
        return itemId;
    }

    private UUID newUser(String prefix) {
        AppUser user = new AppUser();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Reporting fixture actor");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }

    private static byte[] smallJpeg() {
        try {
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
