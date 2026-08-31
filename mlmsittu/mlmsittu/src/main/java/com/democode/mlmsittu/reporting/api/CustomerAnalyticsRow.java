package com.democode.mlmsittu.reporting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One customer, with what they have actually bought (P6-03).
 *
 * <p>Customers who have never ordered are included with zeroes. Dropping them would make the table
 * silently disagree with the customer list, and "who has never bought anything" is one of the more
 * useful questions this table answers.
 */
public record CustomerAnalyticsRow(
        UUID customerId,
        String code,
        String name,
        String city,
        boolean active,
        int orderCount,
        BigDecimal totalValue,
        Instant lastOrderAt,
        /** Null when they have never ordered. Drives the treemap's colour scale. */
        Integer daysSinceLastOrder) {}
