package com.democode.mlmsittu.reporting.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One item's position at one location (P6-01).
 *
 * <p>{@code available} and {@code stockValue} are computed in SQL rather than here, so the figure
 * a CSV export contains and the figure the screen shows come from the same expression. Two
 * implementations of the same arithmetic is how a report and its export end up disagreeing.
 */
public record StockReportRow(
        UUID itemId,
        String sku,
        String name,
        String categoryName,
        UUID locationId,
        String locationName,
        int onHand,
        int reserved,
        int available,
        int reorderLevel,
        boolean belowReorder,
        BigDecimal unitCost,
        BigDecimal stockValue) {}
