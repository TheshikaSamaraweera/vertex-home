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

/**
 * One thing the customer bought.
 *
 * <p>A set stays a set here even though the reservation behind it was expanded into components:
 * the customer bought "Starter Pack", and the invoice has to say so rather than listing six items
 * they never asked for. {@code description} and {@code unitPrice} are copies taken at the moment of
 * sale, because the catalogue moves and a historical order must not move with it.
 */
@Entity
@Table(name = "sales_order_line")
@Getter
@Setter
public class SalesOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    /** Exactly one of these two is set — enforced by {@code chk_sales_line_names_one_thing}. */
    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "set_id")
    private UUID setId;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    /**
     * Stored rather than computed on read.
     *
     * <p>A derived total would silently change if the rounding rule ever changed, rewriting the
     * value of orders that were settled years ago. What was charged is a fact about the past.
     */
    public void recalculate() {
        this.lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
