package com.democode.mlmsittu.rewards.internal.domain;

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

/**
 * One distributor's claim on the pack they chose when they registered.
 *
 * <p>Created when the fourth stage completes, and settled when an administrator hands the goods
 * over. At most one per distributor, enforced by a unique index rather than by hoping — see
 * {@code V19__reward_entitlement.sql} for why that matters.
 */
@Entity
@Table(name = "reward_entitlement")
@Getter
@Setter
public class RewardEntitlement {

    public static final String ELIGIBLE = "eligible";
    public static final String ISSUED = "issued";
    public static final String CANCELLED = "cancelled";

    // Tracking stages, once issued. See V34__reward_tracking.sql.
    public static final String AWAITING_METHOD = "awaiting_method";
    public static final String PREPARING = "preparing";
    public static final String DISPATCHED = "dispatched";
    public static final String COMPLETED = "completed";

    public static final String PICKUP = "pickup";
    public static final String DELIVERY = "delivery";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "distributor_id", nullable = false, updatable = false)
    private UUID distributorId;

    /**
     * Copied from the distributor at the moment of eligibility.
     *
     * <p>Not read live. A set's composition can be edited afterwards, and what somebody earned
     * must not change because a product manager rearranged a bundle six months later.
     */
    @Column(name = "item_set_id", nullable = false, updatable = false)
    private UUID itemSetId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = ELIGIBLE;

    @Column(name = "became_eligible_at", nullable = false, updatable = false)
    private Instant becameEligibleAt = Instant.now();

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "issued_by")
    private UUID issuedBy;

    /** Which store the goods physically came out of. */
    @Column(name = "issued_from_location_id")
    private UUID issuedFromLocationId;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "tracking_number", length = 32)
    private String trackingNumber;

    /** Null until issued; then one of the four tracking stages. */
    @Column(name = "tracking_stage", length = 24)
    private String trackingStage;

    /** {@code pickup} or {@code delivery}; null until somebody chooses. */
    @Column(name = "receive_method", length = 16)
    private String receiveMethod;

    @Column(name = "pickup_location_id")
    private UUID pickupLocationId;

    @Column(name = "delivery_address", length = 500)
    private String deliveryAddress;

    @Column(name = "delivery_contact", length = 32)
    private String deliveryContact;

    @Column(name = "receive_method_set_at")
    private Instant receiveMethodSetAt;

    @Column(name = "receive_method_set_by")
    private UUID receiveMethodSetBy;

    /** The warehouse the pack was finally handed over from. */
    @Column(name = "handover_location_id")
    private UUID handoverLocationId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    public boolean isIssued() {
        return ISSUED.equals(status);
    }

    public void markIssued(UUID locationId, UUID actorId, String note) {
        this.status = ISSUED;
        this.issuedAt = Instant.now();
        this.issuedBy = actorId;
        this.issuedFromLocationId = locationId;
        this.note = note;
    }

    public boolean isCompleted() {
        return COMPLETED.equals(trackingStage);
    }

    public boolean hasReceiveMethod() {
        return receiveMethod != null;
    }

    /** Pickup keeps only the warehouse, delivery only the address — never a mix of both. */
    public void setReceiving(
            String method, UUID pickupLocation, String address, String contact, UUID actorId) {
        this.receiveMethod = method;
        this.pickupLocationId = PICKUP.equals(method) ? pickupLocation : null;
        this.deliveryAddress = DELIVERY.equals(method) ? address : null;
        this.deliveryContact = DELIVERY.equals(method) ? contact : null;
        this.receiveMethodSetAt = Instant.now();
        this.receiveMethodSetBy = actorId;
    }

    public void markCompleted(UUID locationId, UUID actorId) {
        this.trackingStage = COMPLETED;
        this.handoverLocationId = locationId;
        this.completedAt = Instant.now();
        this.completedBy = actorId;
    }
}
