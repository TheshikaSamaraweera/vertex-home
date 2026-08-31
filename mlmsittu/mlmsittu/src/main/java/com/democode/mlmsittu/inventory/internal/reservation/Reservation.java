package com.democode.mlmsittu.inventory.internal.reservation;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "reservation")
@Getter
@Setter
public class Reservation {

    public static final String ACTIVE = "active";
    public static final String RELEASED = "released";
    public static final String CONSUMED = "consumed";
    public static final String EXPIRED = "expired";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reference_type", length = 32)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "status", nullable = false, length = 24)
    private String status = ACTIVE;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "release_reason", length = 64)
    private String releaseReason;

    /**
     * Expanded component quantities. Whatever mix of items and sets was requested, by the time it
     * reaches here it is a flat list of component items — because that is what was actually taken
     * from stock, and release has to give back exactly that.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "reservation_id", nullable = false)
    private List<ReservationLine> lines = new ArrayList<>();

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public void close(String newStatus, String reason) {
        this.status = newStatus;
        this.releasedAt = Instant.now();
        this.releaseReason = reason;
    }
}
