package com.democode.mlmsittu.commerce.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One item, one quantity, one store.
 *
 * <p>The store lives here rather than on the receipt because a single delivery is routinely split
 * across two of them.
 */
@Entity
@Table(name = "goods_receipt_line")
@Getter
@Setter
public class GoodsReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null on a manual entry, which has no order line to close. */
    @Column(name = "purchase_order_line_id")
    private UUID purchaseOrderLineId;

    /**
     * Held on the line itself rather than derived from the order line.
     *
     * <p>Redundant for a receipt against an order, and deliberately so: a manual entry has no
     * order line, and the ledger posting needs an item either way.
     */
    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    /** Which store this line went into. Lines of one receipt may differ. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "quantity_received", nullable = false)
    private int quantityReceived;
}
