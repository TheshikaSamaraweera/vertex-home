package com.democode.mlmsittu.reporting.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the reward packs actually cost, and what they earned.
 *
 * <h2>Issued packs only</h2>
 *
 * <p>A pack that somebody is merely eligible for has cost nothing and earned nothing — no stock
 * has moved and nobody has been credited. Counting those would be counting an intention. The
 * moment a pack is handed over is the moment there is something to account for, and that is the
 * only row this report has.
 *
 * <h2>The two prices</h2>
 *
 * <ul>
 *   <li><b>What the customer paid</b> — {@code item_set.set_price}, the price of the pack they
 *       chose at registration.
 *   <li><b>What it cost</b> — the sum of {@code unit_cost × quantity} across the pack's lines.
 * </ul>
 *
 * <p>Both are read live rather than frozen at issue. That is a real limitation and worth naming:
 * change an item's unit cost today and this report's history changes with it. Freezing them would
 * mean writing a snapshot into {@code reward_entitlement} at issue, which is the right answer and
 * a schema change — this reports on the data that exists.
 *
 * <h2>Commission</h2>
 *
 * <p>Shown beside the profit rather than folded into it. A pack that looks unprofitable is either
 * expensive goods or a large commission, and one number cannot say which.
 */
@Service
public class CostAnalysisService {

    private final JdbcTemplate jdbc;

    public CostAnalysisService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One issued pack, with everything it earned and everything it cost. */
    public record IssuedPack(
            UUID entitlementId,
            Instant issuedAt,
            String businessId,
            String customerName,
            String packCode,
            String packName,
            BigDecimal sellingPrice,
            BigDecimal actualCost,
            BigDecimal grossProfit,
            String officerName,
            BigDecimal commissionRate,
            BigDecimal commission,
            BigDecimal netProfit) {}

    /** The figures at the foot of the table, summed over the same rows. */
    public record CostAnalysisTotals(
            int packsIssued,
            BigDecimal sellingPrice,
            BigDecimal actualCost,
            BigDecimal grossProfit,
            BigDecimal commission,
            BigDecimal netProfit) {}

    public record CostAnalysis(List<IssuedPack> rows, CostAnalysisTotals totals) {}

    /**
     * Every pack issued in the window.
     *
     * <p>The cost is a correlated subquery over the pack's lines rather than a join, deliberately:
     * joining would multiply each entitlement by the number of items in its pack and every total
     * on the page would be wrong by a factor nobody would immediately notice.
     */
    @Transactional(readOnly = true)
    public CostAnalysis analyse(Instant from, Instant to) {
        List<IssuedPack> rows =
                jdbc.query(
                        """
                        WITH pack_cost AS (
                            SELECT l.set_id,
                                   COALESCE(SUM(i.unit_cost * l.quantity), 0) AS cost
                              FROM item_set_line l
                              JOIN item i ON i.id = l.item_id
                             GROUP BY l.set_id
                        )
                        SELECT e.id                                   AS entitlement_id,
                               e.issued_at,
                               d.business_id,
                               u.full_name                            AS customer_name,
                               s.code                                 AS pack_code,
                               s.name                                 AS pack_name,
                               s.set_price                            AS selling_price,
                               COALESCE(c.cost, 0)                    AS actual_cost,
                               officer.full_name                      AS officer_name,
                               COALESCE(mo.commission_rate, 0)        AS commission_rate
                          FROM reward_entitlement e
                          JOIN distributor d      ON d.id = e.distributor_id
                          JOIN app_user u         ON u.id = d.user_id
                          JOIN item_set s         ON s.id = e.item_set_id
                          LEFT JOIN pack_cost c   ON c.set_id = s.id
                          LEFT JOIN app_user officer        ON officer.id = d.marketing_officer_id
                          LEFT JOIN marketing_officer mo    ON mo.user_id = d.marketing_officer_id
                         WHERE e.status = 'issued'
                           AND e.issued_at >= ?
                           AND e.issued_at < ?
                         ORDER BY e.issued_at DESC
                        """,
                        (rs, row) -> {
                            BigDecimal selling = rs.getBigDecimal("selling_price");
                            BigDecimal cost = rs.getBigDecimal("actual_cost");
                            BigDecimal rate = rs.getBigDecimal("commission_rate");

                            BigDecimal gross = selling.subtract(cost);
                            // Rounded to the cent here rather than left at the multiplication's
                            // natural scale, so the column adds up to the total shown beneath it.
                            BigDecimal commission =
                                    selling.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);

                            return new IssuedPack(
                                    rs.getObject("entitlement_id", UUID.class),
                                    rs.getTimestamp("issued_at").toInstant(),
                                    rs.getString("business_id"),
                                    rs.getString("customer_name"),
                                    rs.getString("pack_code"),
                                    rs.getString("pack_name"),
                                    selling,
                                    cost,
                                    gross,
                                    rs.getString("officer_name"),
                                    rate,
                                    commission,
                                    gross.subtract(commission));
                        },
                        java.sql.Timestamp.from(from),
                        java.sql.Timestamp.from(to));

        // Summed from the rows returned, not from a second query. A separate SUM would drift from
        // the table the moment a filter changed on one and not the other, and a total that does
        // not match the column above it destroys confidence in the whole report.
        BigDecimal selling = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;

        for (IssuedPack row : rows) {
            selling = selling.add(row.sellingPrice());
            cost = cost.add(row.actualCost());
            gross = gross.add(row.grossProfit());
            commission = commission.add(row.commission());
            net = net.add(row.netProfit());
        }

        return new CostAnalysis(
                rows,
                new CostAnalysisTotals(rows.size(), selling, cost, gross, commission, net));
    }
}
