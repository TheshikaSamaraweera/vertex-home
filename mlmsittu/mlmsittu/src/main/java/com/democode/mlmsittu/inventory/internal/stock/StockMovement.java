package com.democode.mlmsittu.inventory.internal.stock;

import com.democode.mlmsittu.inventory.api.StockMovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One immutable entry in the stock ledger.
 *
 * <p>Never updated and never deleted — the application database role has no permission to do
 * either. A mistake is corrected by posting a compensating movement, so the record of what was
 * believed, and when, survives the correction.
 */
@Entity
@Table(name = "stock_movement")
@Getter
@Setter
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "qty_delta", nullable = false, updatable = false)
    private int qtyDelta;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, updatable = false, length = 24)
    private StockMovementType movementType;

    @Column(name = "reference_type", updatable = false, length = 32)
    private String referenceType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(name = "reason", updatable = false, length = 64)
    private String reason;

    @Column(name = "note", updatable = false, length = 500)
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
