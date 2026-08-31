package com.democode.mlmsittu.rewards.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What one distributor may be told about their own pack.
 *
 * <p>Separate from the administrator's view on purpose, and much narrower: no availability, no
 * store options, no shortfall. A distributor needs to know whether their pack is coming and
 * whether it has been handed over — which store it came out of is the only warehouse detail that
 * concerns them, and only after the fact.
 */
public interface RewardStatus {

    /**
     * @param status {@code eligible} or {@code issued}
     * @param issuedFromStore null until it has been issued
     */
    record RewardSnapshot(
            UUID entitlementId,
            String status,
            UUID itemSetId,
            String itemSetCode,
            String itemSetName,
            Instant becameEligibleAt,
            Instant issuedAt,
            String issuedFromStore) {}

    /** Empty when they have not completed all four stages, or chose no pack. */
    Optional<RewardSnapshot> forDistributor(UUID distributorId);
}
