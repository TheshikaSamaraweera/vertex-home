package com.democode.mlmsittu.inventory.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Holding stock for a document that has not yet been settled (architecture §4.5).
 *
 * <p>Published so {@code commerce} can hold stock for a sales order without reaching into the
 * sealed stock package. Every method below runs against locked rows taken in a deterministic
 * order; callers get no say in that, which is the point.
 */
public interface StockReservations {

    /**
     * Holds every component of the requested items and sets, or none of them.
     *
     * @param referenceType what the reservation is for, e.g. {@code sales_order}
     * @param ttl null to hold until somebody releases it
     * @throws InsufficientStockException when any component falls short
     */
    ReservationView reserve(
            List<ReservationRequestLine> lines,
            UUID locationId,
            String referenceType,
            UUID referenceId,
            Duration ttl,
            UUID actorId);

    /** Gives the held stock back. Availability recovers immediately. */
    ReservationView release(UUID reservationId, String reason);

    /**
     * Turns held stock into stock that has left the building (P5-08).
     *
     * <p>{@code on_hand} and {@code reserved} both drop by the same quantity, and a negative
     * {@code stock_movement} is appended for each component — <b>all inside one transaction, under
     * the same row locks</b>. Doing it as a release followed by a separate negative posting would
     * open a window in which the stock looks available to anyone reserving concurrently, and would
     * also trip the ledger's own guard that {@code on_hand} may not fall below {@code reserved}.
     */
    ReservationView consume(
            UUID reservationId, String referenceType, UUID referenceId, UUID actorId);

    ReservationView get(UUID reservationId);
}
