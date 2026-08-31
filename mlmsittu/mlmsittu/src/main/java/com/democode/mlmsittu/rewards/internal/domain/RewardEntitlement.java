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
}
