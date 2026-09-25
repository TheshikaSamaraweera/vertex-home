package com.democode.mlmsittu.rewards.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What one distributor may be told about their own pack.
 *
 * <p>Separate from the administrator's view on purpose, and much narrower: no availability, no
 * store options, no shortfall. A distributor needs to know what the pack is, whether it is coming,
 * and — once issued — where it is on its way to them.
 */
public interface RewardStatus {

    /**
     * @param status {@code eligible} or {@code issued}
     * @param issuedFromStore null until it has been issued
     * @param items what is in the pack, with pictures
     * @param tracking null until it has been issued
     */
    record RewardSnapshot(
            UUID entitlementId,
            String status,
            UUID itemSetId,
            String itemSetCode,
            String itemSetName,
            String itemSetDescription,
            UUID itemSetImageId,
            List<PackLine> items,
            Instant becameEligibleAt,
            Instant issuedAt,
            String issuedFromStore,
            Tracking tracking) {}

    /** One item in the pack. Named for the reward so the OpenAPI schema does not collide. */
    record PackLine(
            UUID itemId, String sku, String name, String description, UUID imageId, int quantity) {}

    /**
     * An issued pack's journey.
     *
     * @param stage {@code awaiting_method}, {@code preparing}, {@code dispatched} or {@code
     *     completed}
     * @param receiveMethod {@code pickup} or {@code delivery}; null until chosen. Once set, only
     *     an administrator can change it.
     * @param handoverLocationName the warehouse it was finally handed over from; null until
     *     completed
     * @param history every stage it has reached, oldest first
     */
    record Tracking(
            String trackingNumber,
            String stage,
            String receiveMethod,
            UUID pickupLocationId,
            String pickupLocationName,
            String pickupLocationAddress,
            String deliveryAddress,
            String deliveryContact,
            Instant receiveMethodSetAt,
            String receiveMethodSetByName,
            UUID handoverLocationId,
            String handoverLocationName,
            String handoverLocationAddress,
            Instant completedAt,
            String completedByName,
            List<TrackingStep> history) {}

    /** @param actorName null when the customer took the step themselves */
    record TrackingStep(String stage, String note, String actorName, Instant at) {}

    /** Empty when they have not completed all stages, or chose no pack. */
    Optional<RewardSnapshot> forDistributor(UUID distributorId);

    /**
     * True once their pack has been picked up or delivered — the point at which this business
     * account's journey is over and the portal closes it.
     */
    boolean isCompleted(UUID distributorId);
}
