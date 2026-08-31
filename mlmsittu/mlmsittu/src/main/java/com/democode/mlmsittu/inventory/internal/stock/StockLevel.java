package com.democode.mlmsittu.inventory.internal.stock;

import com.democode.mlmsittu.inventory.api.StockView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * The projection of the stock ledger.
 *
 * <p>Deliberately has no public setters. Every mutation goes through the methods below, which is
 * what stops a caller elsewhere in the module from nudging {@code onHand} without writing a
 * matching movement — the projection would then disagree with the ledger and reconciliation would
 * silently undo the change later.
 *
 * <p>Package-private mutators, package-sealed by ArchUnit. See {@code StockLedgerService}.
 */
@Entity
@Table(name = "stock_level")
@IdClass(StockLevelId.class)
@Getter
public class StockLevel {

    @Id
    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Id
    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    /**
     * Optimistic lock counter. Writes take a pessimistic row lock, so this is belt and braces —
     * it catches a path that somehow updates without locking first.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected StockLevel() {}

    StockLevel(UUID itemId, UUID locationId) {
        this.itemId = itemId;
        this.locationId = locationId;
    }

    public int available() {
        return onHand - reserved;
    }

    public StockView toView() {
        return new StockView(itemId, locationId, onHand, reserved);
    }

    // ------------------------------------------------------------------ mutators

    /** Apply a signed ledger delta. Caller has already validated the result is legal. */
    void applyDelta(int delta) {
        this.onHand += delta;
    }

    /** Set on_hand outright — only ever used by replay reconciliation. */
    void overwriteOnHand(int value) {
        this.onHand = value;
    }

    /** Phase 3 reservation. Increments the reserved subset without touching on_hand. */
    void reserve(int quantity) {
        this.reserved += quantity;
    }

    void release(int quantity) {
        this.reserved -= quantity;
    }

    /**
     * Phase 5 fulfilment. The held stock physically leaves, so both figures fall together.
     *
     * <p>Deliberately not expressible as {@code release} followed by {@code applyDelta}: those are
     * two writes, and between them the row claims the goods are available. Availability must never
     * be observably wrong, not even inside one transaction where a concurrent reader at READ
     * COMMITTED could not see it — because the next person to edit this class will reach for the
     * two-step version if it exists.
     */
    void consume(int quantity) {
        this.onHand -= quantity;
        this.reserved -= quantity;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
