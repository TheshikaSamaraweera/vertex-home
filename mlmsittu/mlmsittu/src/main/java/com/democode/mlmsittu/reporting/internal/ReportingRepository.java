package com.democode.mlmsittu.reporting.internal;

import com.democode.mlmsittu.reporting.api.CustomerAnalyticsRow;
import com.democode.mlmsittu.reporting.api.SalesReport;
import com.democode.mlmsittu.reporting.api.StockReportRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read models, written as SQL (development plan P6-01 to P6-04).
 *
 * <h2>Why this is not JPA</h2>
 *
 * Every query here spans several aggregates and returns a shape no entity has. Expressing that
 * through entities means either loading whole object graphs to sum one column, or writing JPQL that
 * is SQL with the useful parts removed. Reporting is the one place where "just write the query" is
 * the maintainable answer, which is why architecture §1.3 gives it its own module.
 *
 * <h2>Two decisions that run through all of it</h2>
 *
 * <ol>
 *   <li><b>Sales means fulfilled.</b> Only orders that reached {@code fulfilled} count, dated by
 *       {@code fulfilled_at}. That is the one status where the goods actually left and an invoice
 *       exists, so every figure here reconciles against the invoice register. Counting paid-but-
 *       unshipped orders would make "sales" a number that can still go down.
 *   <li><b>Days are Sri Lankan days.</b> Timestamps are {@code timestamptz}; bucketing them by a
 *       raw cast would use whatever zone the JVM happens to run in, so "sales on the 13th" would
 *       change meaning when the server moved. {@link #REPORT_ZONE} pins it. The cost is that the
 *       date filter cannot use an index on {@code fulfilled_at}; at these volumes that is not a
 *       cost worth distorting the answer for.
 * </ol>
 */
@Repository
public class ReportingRepository {

    /** The business's own day boundary. See the class comment. */
    static final String REPORT_ZONE = "Asia/Colombo";

    private static final String LOCAL_DAY =
            "(so.fulfilled_at AT TIME ZONE '" + REPORT_ZONE + "')::date";

    private final JdbcTemplate jdbc;

    public ReportingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==============================================================================
    // P6-01 · stock
    // ==============================================================================

    /**
     * Stock position per item per location.
     *
     * <p>{@code below_reorder} compares against <b>available</b>, not on hand, and matches
     * {@code ReorderScanService} exactly — including that a reorder level of zero means "do not
     * track". Two definitions of "low stock" in one system is how a report and an alert end up
     * contradicting each other on the same screen.
     */
    public List<StockReportRow> stock(
            UUID locationId, UUID categoryId, boolean belowReorderOnly, boolean includeInactive) {

        return jdbc.query(
                """
                SELECT i.id            AS item_id,
                       i.sku,
                       i.name,
                       c.name          AS category_name,
                       l.id            AS location_id,
                       l.name          AS location_name,
                       sl.on_hand,
                       sl.reserved,
                       sl.on_hand - sl.reserved                  AS available,
                       i.reorder_level,
                       i.reorder_level > 0
                         AND (sl.on_hand - sl.reserved) <= i.reorder_level AS below_reorder,
                       i.unit_cost,
                       sl.on_hand * i.unit_cost                  AS stock_value
                FROM stock_level sl
                JOIN item i     ON i.id = sl.item_id
                JOIN location l ON l.id = sl.location_id
                LEFT JOIN category c ON c.id = i.category_id
                WHERE (CAST(? AS uuid) IS NULL OR sl.location_id = CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR i.category_id  = CAST(? AS uuid))
                  AND (? = false OR (i.reorder_level > 0
                                     AND (sl.on_hand - sl.reserved) <= i.reorder_level))
                  AND (? = true  OR i.is_active)
                ORDER BY i.sku, l.name
                """,
                stockRow(),
                locationId, locationId,
                categoryId, categoryId,
                belowReorderOnly,
                includeInactive);
    }

    private RowMapper<StockReportRow> stockRow() {
        return (rs, rowNum) ->
                new StockReportRow(
                        rs.getObject("item_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("category_name"),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("location_name"),
                        rs.getInt("on_hand"),
                        rs.getInt("reserved"),
                        rs.getInt("available"),
                        rs.getInt("reorder_level"),
                        rs.getBoolean("below_reorder"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("stock_value"));
    }

    // ==============================================================================
    // P6-02 · sales
    // ==============================================================================

    /** Totals for the range. Always returns a row — zeroes when nothing sold. */
    public Totals salesTotals(LocalDate from, LocalDate to) {
        return jdbc.queryForObject(
                """
                SELECT count(*)                        AS order_count,
                       COALESCE(sum(so.subtotal), 0)   AS gross_value,
                       COALESCE(sum(so.discount), 0)   AS discount_value,
                       COALESCE(sum(so.total), 0)      AS net_value
                FROM sales_order so
                WHERE so.status = 'fulfilled'
                  AND """ + LOCAL_DAY + """
                       BETWEEN ? AND ?
                """,
                (rs, rowNum) ->
                        new Totals(
                                rs.getInt("order_count"),
                                rs.getBigDecimal("gross_value"),
                                rs.getBigDecimal("discount_value"),
                                rs.getBigDecimal("net_value")),
                from,
                to);
    }

    public record Totals(
            int orderCount, BigDecimal gross, BigDecimal discount, BigDecimal net) {}

    /**
     * One row per day that had sales.
     *
     * <p>Days with none are absent rather than zero-filled — the caller knows the range it asked
     * for and can fill gaps if it wants a continuous axis, whereas a database that invents rows
     * makes "no data" and "zero sales" indistinguishable.
     */
    public List<SalesReport.DailySales> salesByDay(LocalDate from, LocalDate to) {
        return jdbc.query(
                """
                SELECT """ + LOCAL_DAY + """
                        AS day,
                       count(*)                   AS order_count,
                       COALESCE(sum(so.total), 0) AS net_value
                FROM sales_order so
                WHERE so.status = 'fulfilled'
                  AND """ + LOCAL_DAY + """
                       BETWEEN ? AND ?
                GROUP BY 1
                ORDER BY 1
                """,
                (rs, rowNum) ->
                        new SalesReport.DailySales(
                                rs.getObject("day", LocalDate.class),
                                rs.getInt("order_count"),
                                rs.getBigDecimal("net_value")),
                from,
                to);
    }

    /**
     * What sold, by product.
     *
     * <p>A set stays a set: the line said "Starter Pack" and so does this. Expanding it into
     * components here would double-count against the item rows and make the totals disagree with
     * the order the customer actually placed.
     */
    public List<SalesReport.ProductSales> topProducts(LocalDate from, LocalDate to, int limit) {
        return jdbc.query(
                """
                SELECT COALESCE(sol.item_id, sol.set_id)::text AS product_id,
                       max(sol.description)                    AS description,
                       sum(sol.quantity)                       AS quantity,
                       sum(""" + DISCOUNTED_LINE + """
                       )                                       AS net_value
                FROM sales_order_line sol
                JOIN sales_order so ON so.id = sol.sales_order_id
                WHERE so.status = 'fulfilled'
                  AND """ + LOCAL_DAY + """
                       BETWEEN ? AND ?
                GROUP BY 1
                ORDER BY net_value DESC
                LIMIT ?
                """,
                (rs, rowNum) ->
                        new SalesReport.ProductSales(
                                rs.getString("product_id"),
                                rs.getString("description"),
                                rs.getLong("quantity"),
                                rs.getBigDecimal("net_value")),
                from,
                to,
                limit);
    }

    /**
     * A line's share of what was actually charged.
     *
     * <p>The discount is recorded once on the order, not spread across its lines. Summing raw
     * {@code line_total} would therefore produce a figure larger than the money taken, and any
     * breakdown built from it would refuse to add up to the report's own net total. Prorating by
     * each line's share of the subtotal fixes that; the {@code subtotal > 0} guard is for the
     * fully-discounted order, where every share is zero anyway.
     */
    private static final String DISCOUNTED_LINE =
            "sol.line_total * CASE WHEN so.subtotal > 0 THEN so.total / so.subtotal ELSE 1 END";

    // ==============================================================================
    // P6-03 · customers
    // ==============================================================================

    /**
     * Every customer, with what they have bought.
     *
     * <p>A {@code LEFT JOIN} onto a grouped subquery rather than a correlated subquery per column:
     * one pass over {@code sales_order} instead of three, which is the difference between this
     * being usable at five hundred customers and not.
     */
    public List<CustomerAnalyticsRow> customerAnalytics(boolean includeInactive) {
        return jdbc.query(
                """
                SELECT c.id, c.code, c.name, c.city, c.is_active,
                       COALESCE(s.order_count, 0) AS order_count,
                       COALESCE(s.total_value, 0) AS total_value,
                       s.last_order_at
                FROM customer c
                LEFT JOIN (
                    SELECT so.customer_id,
                           count(*)        AS order_count,
                           sum(so.total)   AS total_value,
                           max(so.fulfilled_at) AS last_order_at
                    FROM sales_order so
                    WHERE so.status = 'fulfilled'
                    GROUP BY so.customer_id
                ) s ON s.customer_id = c.id
                WHERE (? = true OR c.is_active)
                ORDER BY c.name
                """,
                (rs, rowNum) -> {
                    Instant last = toInstant(rs.getObject("last_order_at", java.sql.Timestamp.class));
                    return new CustomerAnalyticsRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("city"),
                            rs.getBoolean("is_active"),
                            rs.getInt("order_count"),
                            rs.getBigDecimal("total_value"),
                            last,
                            daysSince(last));
                },
                includeInactive);
    }

    // ==============================================================================
    // P6-04 · treemap
    // ==============================================================================

    /**
     * Net sales per (category, customer) pair.
     *
     * <p>Uses the same prorated line value as {@link #topProducts}, so the treemap's areas sum to
     * the sales report's net total. A treemap whose rectangles add up to a different number than
     * the report beside it is worse than no treemap.
     */
    public List<TreemapCell> treemapCells(LocalDate from, LocalDate to) {
        return jdbc.query(
                """
                SELECT COALESCE(
                           cat.name,
                           CASE WHEN sol.set_id IS NOT NULL THEN 'Sets' ELSE 'Uncategorised' END
                       )                                    AS category_name,
                       cust.name                            AS customer_name,
                       sum(""" + DISCOUNTED_LINE + """
                       )                                    AS value,
                       max(so.fulfilled_at)                 AS last_order_at
                FROM sales_order_line sol
                JOIN sales_order so   ON so.id = sol.sales_order_id
                JOIN customer cust    ON cust.id = so.customer_id
                LEFT JOIN item it     ON it.id = sol.item_id
                LEFT JOIN category cat ON cat.id = it.category_id
                WHERE so.status = 'fulfilled'
                  AND """ + LOCAL_DAY + """
                       BETWEEN ? AND ?
                GROUP BY 1, 2
                HAVING sum(""" + DISCOUNTED_LINE + """
                       ) > 0
                ORDER BY 1, value DESC
                """,
                (rs, rowNum) -> {
                    Instant last = toInstant(rs.getObject("last_order_at", java.sql.Timestamp.class));
                    return new TreemapCell(
                            rs.getString("category_name"),
                            rs.getString("customer_name"),
                            rs.getBigDecimal("value"),
                            daysSince(last));
                },
                from,
                to);
    }

    public record TreemapCell(
            String categoryName, String customerName, BigDecimal value, Integer daysSinceLastOrder) {}

    // ==============================================================================
    // helpers
    // ==============================================================================

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Integer daysSince(Instant moment) {
        if (moment == null) {
            return null;
        }
        return (int) java.time.Duration.between(moment, Instant.now()).toDays();
    }
}
