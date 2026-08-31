package com.democode.mlmsittu.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sales over a date range (P6-02).
 *
 * <p>An empty range is a valid answer, not an error: zero orders, zero value, empty lists. A report
 * that throws on "no sales last Tuesday" forces every caller to distinguish a real failure from a
 * quiet week.
 *
 * @param from inclusive
 * @param to inclusive — the service widens it to the end of that day before querying, because
 *     nobody means "up to midnight" when they type a date
 */
public record SalesReport(
        LocalDate from,
        LocalDate to,
        int orderCount,
        BigDecimal grossValue,
        BigDecimal discountValue,
        BigDecimal netValue,
        BigDecimal averageOrderValue,
        List<DailySales> daily,
        List<ProductSales> topProducts) {

    public record DailySales(LocalDate day, int orderCount, BigDecimal netValue) {}

    /** @param productId the item or set id, whichever the line named */
    public record ProductSales(
            String productId,
            String description,
            long quantity,
            BigDecimal netValue) {}
}
