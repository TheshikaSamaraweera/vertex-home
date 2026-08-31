package com.democode.mlmsittu.commerce.internal.domain;

import java.util.Arrays;

/**
 * Mirrors {@code chk_sales_order_status}.
 *
 * <p>The shape of the flow, in one place:
 *
 * <pre>
 *   AWAITING_PAYMENT --slip recorded--> PAYMENT_REVIEW --verified--> PAID --fulfil--> FULFILLED
 *          |                                   |
 *          |                                   +--rejected--> PAYMENT_REJECTED --new slip-->
 *          |                                                        (re-reserves, back to review)
 *          +--cancel--> CANCELLED
 * </pre>
 */
public enum SalesOrderStatus {
    AWAITING_PAYMENT("awaiting_payment"),
    PAYMENT_REVIEW("payment_review"),
    PAID("paid"),
    FULFILLED("fulfilled"),
    PAYMENT_REJECTED("payment_rejected"),
    CANCELLED("cancelled");

    private final String dbValue;

    SalesOrderStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static SalesOrderStatus fromDbValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.dbValue.equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown sales order status: " + value));
    }

    /** True while the order still holds stock that a cancellation would have to give back. */
    public boolean holdsStock() {
        return this == AWAITING_PAYMENT || this == PAYMENT_REVIEW || this == PAID;
    }
}
