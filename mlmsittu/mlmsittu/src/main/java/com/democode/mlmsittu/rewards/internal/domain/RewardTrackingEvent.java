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
import lombok.NoArgsConstructor;

/** One step in an issued pack's journey: the stage it reached, when, and who moved it. */
@Entity
@Table(name = "reward_tracking_event")
@Getter
@NoArgsConstructor
public class RewardTrackingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "entitlement_id", nullable = false, updatable = false)
    private UUID entitlementId;

    @Column(name = "stage", nullable = false, length = 24, updatable = false)
    private String stage;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    /** Null when the customer moved it themselves, by choosing how to receive it. */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "at", nullable = false, updatable = false)
    private Instant at = Instant.now();

    public RewardTrackingEvent(UUID entitlementId, String stage, String note, UUID actorId) {
        this.entitlementId = entitlementId;
        this.stage = stage;
        this.note = note;
        this.actorId = actorId;
    }
}
