package com.democode.mlmsittu.reporting.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * The stock report and its totals.
 *
 * <p>The totals are summed from the same rows that are returned, not queried separately. A separate
 * aggregate query can disagree with its own detail — different filters, a different snapshot — and
 * the reader has no way to tell which half is wrong.
 */
public record StockReport(
        List<StockReportRow> rows,
        int itemCount,
        long totalOnHand,
        long totalReserved,
        BigDecimal totalValue,
        int belowReorderCount) {

    public static StockReport of(List<StockReportRow> rows) {
        return new StockReport(
                rows,
                rows.size(),
                rows.stream().mapToLong(StockReportRow::onHand).sum(),
                rows.stream().mapToLong(StockReportRow::reserved).sum(),
                rows.stream()
                        .map(StockReportRow::stockValue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                (int) rows.stream().filter(StockReportRow::belowReorder).count());
    }
}
