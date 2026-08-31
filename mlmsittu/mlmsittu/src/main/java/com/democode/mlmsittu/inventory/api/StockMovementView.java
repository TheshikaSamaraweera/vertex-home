package com.democode.mlmsittu.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A ledger entry as seen from outside the stock aggregate.
 *
 * <p>The {@code StockMovement} entity itself never leaves its package — an ArchUnit rule sees to
 * that. Handing out a managed entity would put a Hibernate-attached object in reach of code that
 * has no business mutating it, and the entity is meant to be immutable once written.
 */
public record StockMovementView(
        long id,
        UUID itemId,
        UUID locationId,
        int qtyDelta,
        StockMovementType type,
        String referenceType,
        UUID referenceId,
        String reason,
        String note,
        UUID createdBy,
        Instant createdAt) {}
