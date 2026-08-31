package com.democode.mlmsittu.commerce.internal.domain;

/**
 * Purchase order lifecycle (flow F-07).
 *
 * <pre>
 *   draft ─send─▶ sent ─confirm arrival─▶ arrived ─add to stores─▶ partially_received ─▶ received
 *     │            │                        │                          │
 *     └────────────┴────────────────────────┘───────cancel──▶ cancelled
 * </pre>
 *
 * <p>{@link #ARRIVED} was added when receiving was split in two. Before it, booking a delivery
 * and putting it on a shelf were one action; the warehouse does them separately, so a lorry that
 * has been signed for but not yet unpacked now has a status of its own. Stock still moves only at
 * the second step — an order sitting in {@code arrived} has changed no quantity anywhere.
 */
public enum PurchaseOrderStatus {

    /** Editable. Lines can be added, changed and removed. */
    DRAFT("draft"),

    /** Issued to the supplier. Lines are frozen — see {@code PurchaseOrderService#assertEditable}. */
    SENT("sent"),

    /**
     * Somebody has confirmed the delivery physically turned up, by typing their own name.
     *
     * <p>No stock has moved. This is the state a delivery sits in between the door and the shelf.
     */
    ARRIVED("arrived"),

    /** Some quantity has arrived, but at least one line is still short. */
    PARTIALLY_RECEIVED("partially_received"),

    /** Every line is fully received. No further receipt is accepted. */
    RECEIVED("received"),

    CANCELLED("cancelled");

    private final String dbValue;

    PurchaseOrderStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /**
     * Whether goods on this order may still be put into a store.
     *
     * <p>Deliberately excludes {@link #SENT}: an order nobody has confirmed as arrived cannot have
     * its contents shelved, because there is no attestation that anything came. That refusal is
     * the whole point of the arrival step.
     */
    public boolean isOpenForReceipt() {
        return this == ARRIVED || this == PARTIALLY_RECEIVED;
    }

    /** Only a sent order can be confirmed as arrived; a draft was never issued to anybody. */
    public boolean canConfirmArrival() {
        return this == SENT;
    }

    public static PurchaseOrderStatus fromDbValue(String value) {
        for (PurchaseOrderStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown purchase_order.status value");
    }
}
