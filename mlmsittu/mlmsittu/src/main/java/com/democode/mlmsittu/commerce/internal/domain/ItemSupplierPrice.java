package com.democode.mlmsittu.commerce.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * What one supplier charges for one item.
 *
 * <p>Lives in commerce rather than the catalogue because it references a supplier, and an item has
 * no business knowing suppliers exist — what a product <em>is</em> and what it costs to buy are
 * different facts owned by different parts of the business.
 */
@Entity
@Table(name = "item_supplier_price")
@Getter
@Setter
public class ItemSupplierPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    /** The terms the number was agreed under — "per case of 24", "landed, excludes duty". */
    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
