package com.democode.mlmsittu.catalogue.internal.domain;

import com.democode.mlmsittu.catalogue.api.ItemRef;
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

@Entity
@Table(name = "item")
@Getter
@Setter
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Stored upper-cased. See {@code ItemService#normaliseSku}. */
    @Column(name = "sku", nullable = false, unique = true, length = 64)
    private String sku;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    /** Plain id, not a {@code @ManyToOne} — category lives in this module, but the pattern is the
     * one the architecture mandates across modules, and keeping it uniform avoids a lazy-loading
     * surprise the day categories move. */
    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "selling_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal sellingPrice;

    /**
     * Reference prices, quoted to customers. Deliberately <b>not</b> what an order charges — that
     * remains {@code sellingPrice}, so adding these changed nothing about the sales flow.
     *
     * <p>Null means "not set", which is a different statement from zero. Only a super admin may
     * write them; see {@code CatalogueController}.
     */
    @Column(name = "retail_price")
    private BigDecimal retailPrice;

    @Column(name = "wholesale_price")
    private BigDecimal wholesalePrice;

    @Column(name = "reorder_level", nullable = false)
    private int reorderLevel;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public ItemRef toRef() {
        return new ItemRef(id, sku, name, unitCost, sellingPrice, reorderLevel, active);
    }
}
