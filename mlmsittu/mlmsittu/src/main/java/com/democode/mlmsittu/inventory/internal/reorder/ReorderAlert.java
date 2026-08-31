package com.democode.mlmsittu.inventory.internal.reorder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "reorder_alert")
@Getter
@Setter
public class ReorderAlert {

    public static final String OPEN = "open";
    public static final String CLEARED = "cleared";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    /**
     * Null since V18. The threshold is judged on the item total across every store; older rows
     * keep the store they were raised against, because that is what actually happened.
     */
    @Column(name = "location_id", updatable = false)
    private UUID locationId;

    /** The threshold as it stood when the alert fired, not as it stands now. */
    @Column(name = "reorder_level", nullable = false)
    private int reorderLevel;

    @Column(name = "on_hand_at_detection", nullable = false)
    private int onHandAtDetection;

    @Column(name = "status", nullable = false, length = 24)
    private String status = OPEN;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt = Instant.now();

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "on_hand_at_clearance")
    private Integer onHandAtClearance;

    public void clear(int onHandNow) {
        this.status = CLEARED;
        this.clearedAt = Instant.now();
        this.onHandAtClearance = onHandNow;
    }
}
