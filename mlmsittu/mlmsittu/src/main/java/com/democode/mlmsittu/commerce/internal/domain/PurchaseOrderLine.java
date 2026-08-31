package com.democode.mlmsittu.commerce.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "purchase_order_line")
@Getter
@Setter
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "quantity_ordered", nullable = false)
    private int quantityOrdered;

    /**
     * Running total across every receipt against this line. A database CHECK keeps it at or below
     * {@code quantityOrdered}, so an over-receipt cannot survive even if a code path forgets to
     * ask.
     */
    @Column(name = "quantity_received", nullable = false)
    private int quantityReceived;

    /** Copied from the item at order time — the agreed price, not today's catalogue price. */
    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitCost;

    public int outstanding() {
        return quantityOrdered - quantityReceived;
    }

    public boolean isClosed() {
        return outstanding() <= 0;
    }

    public BigDecimal lineTotal() {
        return unitCost.multiply(BigDecimal.valueOf(quantityOrdered));
    }

    public void receive(int quantity) {
        this.quantityReceived += quantity;
    }
}
