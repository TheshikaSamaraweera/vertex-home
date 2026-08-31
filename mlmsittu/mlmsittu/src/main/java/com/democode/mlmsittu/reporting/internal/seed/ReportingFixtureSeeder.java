package com.democode.mlmsittu.reporting.internal.seed;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enough history to make the Phase 6 reports say something (development plan P6-03, P6-04).
 *
 * <p>Off by default. Turn it on for a run:
 *
 * <pre>
 *   ./gradlew bootRun --args="--spring.profiles.active=seed --mlmsittu.seed.report-customers=500"
 * </pre>
 *
 * <p>P6-03 asks for five hundred customers so the load time can be measured now and compared after
 * Phase 7 paginates it, and a treemap of four rectangles proves nothing about a treemap. Neither is
 * something you want appearing on every developer's machine by default, hence the flag.
 *
 * <h2>These orders bypass the stock ledger, on purpose</h2>
 *
 * The rows are written with {@code JdbcTemplate} directly: fulfilled sales orders, backdated across
 * the past year, with <b>no reservation, no stock movement and no invoice</b>. That is deliberate
 * and it is the honest way to fake history.
 *
 * <p>Going through {@code SalesOrderService} would reserve and then consume stock at <em>today's</em>
 * levels, so seeding a year of sales would need a year of purchases first, and the closing stock
 * figures would be fiction anyway. Writing the movements by hand would be worse: the ledger is the
 * source of truth for stock, and inventing entries in it would corrupt the one table this system
 * treats as evidence.
 *
 * <p>The consequence is a property worth stating plainly: <b>seeded sales history has no effect on
 * stock</b>. Stock reconciliation still reports zero corrections, because no movements were
 * invented. The sales and customer reports read {@code sales_order} and are fully populated; the
 * stock report reads {@code stock_level} and is untouched. Anything you fulfil by hand afterwards
 * behaves normally.
 */
@Component
@Profile("seed")
@Order(70)
public class ReportingFixtureSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportingFixtureSeeder.class);

    /** Fixed seed: the same run twice produces the same figures, so a cross-check is repeatable. */
    private static final long RANDOM_SEED = 20260815L;

    private static final int DAYS_OF_HISTORY = 365;

    private static final String[] FIRST_NAMES = {
        "Nimal", "Kumari", "Sunil", "Priya", "Ruwan", "Dilani", "Suresh", "Chamari",
        "Asanka", "Nadeeka", "Tharindu", "Ishara", "Kasun", "Sanduni", "Chathura", "Hasini"
    };

    private static final String[] LAST_NAMES = {
        "Fernando", "Perera", "Silva", "Jayasinghe", "Wickramasinghe", "Rajapaksa",
        "Bandara", "Gunawardena", "Ratnayake", "Dissanayake", "Herath", "Senanayake"
    };

    private static final String[] CITIES = {
        "Colombo", "Kandy", "Galle", "Negombo", "Jaffna", "Matara", "Kurunegala", "Anuradhapura"
    };

    private final JdbcTemplate jdbc;

    /** 0 means "do nothing", which is the default. */
    @Value("${mlmsittu.seed.report-customers:0}")
    private int customerTarget;

    public ReportingFixtureSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (customerTarget <= 0) {
            return;
        }

        Integer existing =
                jdbc.queryForObject(
                        "SELECT count(*) FROM customer WHERE code LIKE 'RPT-%'", Integer.class);
        if (existing != null && existing > 0) {
            log.info("Seed: reporting — {} fixture customers already present, leaving them", existing);
            return;
        }

        UUID owner = anyActiveUser();
        List<SellableItem> items = sellableItems();
        UUID location = defaultLocation();

        if (owner == null || items.isEmpty() || location == null) {
            log.warn("Seed: reporting — no users, items or location yet; skipping fixtures");
            return;
        }

        Random random = new Random(RANDOM_SEED);
        List<UUID> customers = createCustomers(customerTarget, owner, random);
        int orders = createOrderHistory(customers, items, location, owner, random);

        log.info(
                """

                ==============================================================================
                  SEED: reporting fixtures
                ==============================================================================
                  Customers created     : {}
                  Fulfilled orders      : {} across the last {} days
                  Stock effect          : NONE — see ReportingFixtureSeeder's class comment
                ==============================================================================""",
                customers.size(),
                orders,
                DAYS_OF_HISTORY);
    }

    // ------------------------------------------------------------------ customers

    private List<UUID> createCustomers(int count, UUID owner, Random random) {
        List<Object[]> batch = new ArrayList<>(count);
        List<UUID> ids = new ArrayList<>(count);

        for (int index = 1; index <= count; index++) {
            UUID id = UUID.randomUUID();
            ids.add(id);

            String name =
                    FIRST_NAMES[random.nextInt(FIRST_NAMES.length)]
                            + " "
                            + LAST_NAMES[random.nextInt(LAST_NAMES.length)];

            batch.add(
                    new Object[] {
                        id,
                        String.format("RPT-%04d", index),
                        name,
                        CITIES[random.nextInt(CITIES.length)],
                        "+9477" + String.format("%07d", random.nextInt(10_000_000)),
                        owner
                    });
        }

        jdbc.batchUpdate(
                """
                INSERT INTO customer (id, code, name, city, phone, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                batch);
        return ids;
    }

    // ------------------------------------------------------------------ order history

    /**
     * A long tail, not a uniform spread.
     *
     * <p>Real customer value is heavily skewed, and a treemap of five hundred identical rectangles
     * would look correct while testing nothing — the whole point of P6-04 is checking that area is
     * proportional to value, which needs values that visibly differ. So a fifth of the customers get
     * most of the orders.
     */
    private int createOrderHistory(
            List<UUID> customers,
            List<SellableItem> items,
            UUID location,
            UUID owner,
            Random random) {

        List<Object[]> orderRows = new ArrayList<>();
        List<Object[]> lineRows = new ArrayList<>();
        int orderNumber = 900_000; // well clear of anything the sequence will hand out

        for (int index = 0; index < customers.size(); index++) {
            boolean heavy = index % 5 == 0;
            int orderCount = heavy ? 3 + random.nextInt(8) : random.nextInt(3);

            for (int n = 0; n < orderCount; n++) {
                UUID orderId = UUID.randomUUID();
                Instant when =
                        Instant.now()
                                .minus(random.nextInt(DAYS_OF_HISTORY), ChronoUnit.DAYS)
                                .minus(random.nextInt(24), ChronoUnit.HOURS);

                BigDecimal subtotal = BigDecimal.ZERO;
                List<Object[]> theseLines = new ArrayList<>();

                int lineCount = 1 + random.nextInt(3);
                for (int lineNo = 1; lineNo <= lineCount; lineNo++) {
                    SellableItem item = items.get(random.nextInt(items.size()));
                    int quantity = 1 + random.nextInt(heavy ? 20 : 5);
                    BigDecimal unitPrice =
                            BigDecimal.valueOf(50 + random.nextInt(950)).setScale(2);
                    BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
                    subtotal = subtotal.add(lineTotal);

                    theseLines.add(
                            new Object[] {
                                UUID.randomUUID(), orderId, lineNo, item.id(),
                                // The real product name, so the top-products breakdown reads like
                                // a report rather than a list of "Seeded sale" repeated ten times.
                                item.label(), quantity, unitPrice, lineTotal
                            });
                }

                // A discount on roughly one order in four, so the prorating in the reports has
                // something to prove itself against.
                BigDecimal discount =
                        random.nextInt(4) == 0
                                ? subtotal.multiply(BigDecimal.valueOf(0.10))
                                        .setScale(2, java.math.RoundingMode.HALF_UP)
                                : BigDecimal.ZERO;

                orderRows.add(
                        new Object[] {
                            orderId,
                            String.format("SO-%06d", orderNumber++),
                            customers.get(index),
                            location,
                            subtotal,
                            discount,
                            subtotal.subtract(discount),
                            owner,
                            java.sql.Timestamp.from(when),
                            java.sql.Timestamp.from(when),
                            java.sql.Timestamp.from(when)
                        });
                lineRows.addAll(theseLines);
            }
        }

        jdbc.batchUpdate(
                """
                INSERT INTO sales_order
                    (id, order_number, customer_id, location_id, status,
                     subtotal, discount, total, created_by, created_at, paid_at, fulfilled_at)
                VALUES (?, ?, ?, ?, 'fulfilled', ?, ?, ?, ?, ?, ?, ?)
                """,
                orderRows);

        jdbc.batchUpdate(
                """
                INSERT INTO sales_order_line
                    (id, sales_order_id, line_no, item_id, description, quantity, unit_price, line_total)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                lineRows);

        return orderRows.size();
    }

    // ------------------------------------------------------------------ lookups

    private UUID anyActiveUser() {
        return first(
                "SELECT id FROM app_user WHERE status = 'active' ORDER BY created_at LIMIT 1");
    }

    private UUID defaultLocation() {
        return first("SELECT id FROM location ORDER BY created_at LIMIT 1");
    }

    private record SellableItem(UUID id, String label) {}

    private List<SellableItem> sellableItems() {
        return jdbc.query(
                "SELECT id, sku, name FROM item WHERE is_active ORDER BY sku",
                (rs, rowNum) ->
                        new SellableItem(
                                rs.getObject("id", UUID.class),
                                rs.getString("sku") + " · " + rs.getString("name")));
    }

    private UUID first(String sql) {
        return jdbc.query(sql, (rs, rowNum) -> rs.getObject(1, UUID.class)).stream()
                .findFirst()
                .orElse(null);
    }
}
