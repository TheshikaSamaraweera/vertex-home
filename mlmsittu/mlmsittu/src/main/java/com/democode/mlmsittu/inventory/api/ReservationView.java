package com.democode.mlmsittu.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A reservation as the rest of the system sees it.
 *
 * <p>Lines are expanded components, not whatever mix of items and sets was asked for. That is what
 * was actually taken from stock, and it is what release or fulfilment has to give back or consume.
 */
public record ReservationView(
        UUID id,
        String status,
        UUID locationId,
        String referenceType,
        UUID referenceId,
        Instant createdAt,
        Instant expiresAt,
        Instant releasedAt,
        String releaseReason,
        List<ReservationLineView> lines) {

    public boolean isActive() {
        return "active".equals(status);
    }
}
