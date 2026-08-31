package com.democode.mlmsittu.inventory.internal.stock;

import com.democode.mlmsittu.catalogue.api.ItemSetCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemSetRef;
import com.democode.mlmsittu.inventory.api.InsufficientStockException;
import com.democode.mlmsittu.inventory.api.ReservationLineView;
import com.democode.mlmsittu.inventory.api.ReservationRequestLine;
import com.democode.mlmsittu.inventory.api.ReservationView;
import com.democode.mlmsittu.inventory.api.StockMovementType;
import com.democode.mlmsittu.inventory.api.StockReservations;
import com.democode.mlmsittu.inventory.internal.reservation.Reservation;
import com.democode.mlmsittu.inventory.internal.reservation.ReservationLine;
import com.democode.mlmsittu.inventory.internal.reservation.ReservationRepository;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.datasource.ReadFromPrimary;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock reservation — the concurrency-critical path (architecture §4.5, development plan P3-04).
 *
 * <p>Lives in the sealed {@code stock} package because it is the only other class permitted to
 * touch {@code stock_level}. Reserving changes {@code reserved} and never {@code on_hand}: reserved
 * stock is still physically present. It leaves on {@link #consume}, which is Phase 5's fulfilment.
 *
 * <h2>Lock ordering is not optional</h2>
 *
 * Two reservations touching the same components in opposite sequence will deadlock under load.
 * {@link #lockOrder} sorts by item id before anything is locked, which makes that impossible. This
 * is the single most important method in the class, and {@code ReservationConcurrencyTest} proves
 * both that it works and that the test would catch its removal.
 */
@ReadFromPrimary
@Service
public class ReservationService implements StockReservations {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final ReservationRepository reservations;
    private final ItemSetCatalogue setCatalogue;

    public ReservationService(
            StockLevelRepository levels,
            StockMovementRepository movements,
            ReservationRepository reservations,
            ItemSetCatalogue setCatalogue) {
        this.levels = levels;
        this.movements = movements;
        this.reservations = reservations;
        this.setCatalogue = setCatalogue;
    }

    // ------------------------------------------------------------------ reserve

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Audited(action = "STOCK_RESERVED", entityType = "reservation", auditFailures = true)
    public ReservationView reserve(
            List<ReservationRequestLine> requestLines,
            UUID locationId,
            String referenceType,
            UUID referenceId,
            Duration ttl,
            UUID actorId) {

        Map<UUID, Integer> required = expandToComponents(requestLines);
        if (required.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "RESERVATION_EMPTY",
                    "A reservation needs at least one item.");
        }

        // Deterministic lock ordering — MANDATORY. See the class comment.
        List<UUID> ordered = lockOrder(required);

        for (UUID itemId : ordered) {
            int need = required.get(itemId);

            StockLevel stock =
                    levels.lockForUpdate(itemId, locationId)
                            .orElseThrow(
                                    () -> {
                                        // No row means the item has never moved here, so there is
                                        // nothing to reserve. Distinct from "not enough" on
                                        // purpose: it usually means the wrong location.
                                        NotFoundException notFound =
                                                new NotFoundException(
                                                        "STOCK_ROW_MISSING",
                                                        "That item has no stock position at this"
                                                            + " location.");
                                        notFound.with("itemId", itemId);
                                        notFound.with("locationId", locationId);
                                        return notFound;
                                    });

            if (stock.available() < need) {
                // Throwing here rolls the whole transaction back, so the rows already updated in
                // this loop are undone. A reservation is all or nothing — a half-reserved order
                // holds stock nobody can account for.
                throw new InsufficientStockException(
                        itemId, locationId, need, stock.available());
            }

            stock.reserve(need);
            levels.save(stock);
        }

        Reservation reservation = new Reservation();
        reservation.setLocationId(locationId);
        reservation.setReferenceType(referenceType);
        reservation.setReferenceId(referenceId);
        reservation.setCreatedBy(actorId);
        reservation.setExpiresAt(ttl == null ? null : Instant.now().plus(ttl));

        for (UUID itemId : ordered) {
            ReservationLine line = new ReservationLine();
            line.setItemId(itemId);
            line.setQuantity(required.get(itemId));
            reservation.getLines().add(line);
        }

        Reservation saved = reservations.save(reservation);
        AuditContext.record(saved.getId(), null, Map.of("components", required, "locationId", locationId));
        return toView(saved);
    }

    /**
     * The sort that prevents deadlock.
     *
     * <p><b>To prove the concurrency test is real</b> (development plan P3-05): delete
     * {@code .sorted()} from the line below and run
     * {@code ./gradlew test --tests ReservationConcurrencyTest}. The test must fail with
     * deadlocks. Put it back afterwards.
     *
     * <p>Removing it leaves the map's insertion order, which follows the caller's request order —
     * so two callers listing the same items in opposite order lock them in opposite order, which
     * is exactly the deadlock.
     */
    private List<UUID> lockOrder(Map<UUID, Integer> required) {
        return required.keySet().stream().sorted().toList();
    }

    /**
     * Flattens items and sets into component quantities.
     *
     * <p>Returns a {@link LinkedHashMap}, so iteration follows the caller's request order. That is
     * deliberate: it means {@link #lockOrder} is the <em>only</em> thing imposing a consistent
     * order, so removing the sort genuinely reproduces the bug rather than being masked by a hash
     * order that happened to agree.
     */
    private Map<UUID, Integer> expandToComponents(List<ReservationRequestLine> requestLines) {
        Map<UUID, Integer> required = new LinkedHashMap<>();

        for (ReservationRequestLine line : requestLines) {
            if (line.quantity() <= 0) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_QUANTITY",
                        "Quantity must be greater than zero.");
            }
            boolean hasItem = line.itemId() != null;
            boolean hasSet = line.setId() != null;
            if (hasItem == hasSet) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_RESERVATION_LINE",
                        "Each line must name either an item or a set, not both and not neither.");
            }

            if (hasItem) {
                required.merge(line.itemId(), line.quantity(), Integer::sum);
                continue;
            }

            ItemSetRef set =
                    setCatalogue
                            .findById(line.setId())
                            .orElseThrow(
                                    () ->
                                            new NotFoundException(
                                                    "ITEM_SET_NOT_FOUND",
                                                    "No item set with that id."));
            if (!set.active()) {
                throw new ConflictException(
                        "ITEM_SET_INACTIVE", "That set is deactivated and cannot be reserved.");
            }

            // Duplicates across lines are merged, so a request for two sets sharing a component
            // is checked against the combined requirement rather than each half passing alone.
            set.components()
                    .forEach(
                            (itemId, perSet) ->
                                    required.merge(itemId, perSet * line.quantity(), Integer::sum));
        }

        return required;
    }

    // ------------------------------------------------------------------ release

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Audited(action = "RESERVATION_RELEASED", entityType = "reservation", auditFailures = true)
    public ReservationView release(UUID reservationId, String reason) {
        return toView(closeReservation(reservationId, Reservation.RELEASED, reason));
    }

    /** Used by the expiry sweep. Same mechanics, different resulting status. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Audited(action = "RESERVATION_EXPIRED", entityType = "reservation")
    public ReservationView expire(UUID reservationId) {
        return toView(closeReservation(reservationId, Reservation.EXPIRED, "expired"));
    }

    private Reservation closeReservation(UUID reservationId, String newStatus, String reason) {
        Reservation reservation = lockActive(reservationId);

        for (ReservationLine line : inLockOrder(reservation)) {
            StockLevel stock = lockLevelFor(reservation, line);
            stock.release(line.getQuantity());
            levels.save(stock);
        }

        reservation.close(newStatus, reason);
        Reservation saved = reservations.save(reservation);

        AuditContext.record(
                reservationId,
                Map.of("status", Reservation.ACTIVE),
                Map.of("status", newStatus, "reason", reason == null ? "" : reason));

        log.info("Reservation {} closed as {} ({})", reservationId, newStatus, reason);
        return saved;
    }

    // ------------------------------------------------------------------ consume

    /**
     * Fulfilment (P5-08). The held stock leaves.
     *
     * <h2>Why this is not release-then-post</h2>
     *
     * Releasing and then posting a negative movement through {@code StockLedgerService} would be
     * two transactions with a gap between them in which the stock reads as available, so a
     * concurrent reservation could take goods that are already on a van. It would also be refused
     * outright by the ledger's guard that {@code on_hand} may not fall below {@code reserved},
     * which is a real invariant and should not be worked around.
     *
     * <p>So both halves move together under one lock: {@code on_hand} and {@code reserved} each
     * fall by the line quantity, and the movement is appended in the same transaction. The row
     * lock is held by this method for the same reason and in the same sorted order as everywhere
     * else in this class.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Audited(action = "RESERVATION_CONSUMED", entityType = "reservation", auditFailures = true)
    public ReservationView consume(
            UUID reservationId, String referenceType, UUID referenceId, UUID actorId) {

        if (actorId == null) {
            // Every movement is attributable — the same rule StockLedgerService enforces.
            throw new IllegalArgumentException("Consuming a reservation requires an actorId");
        }

        Reservation reservation = lockActive(reservationId);

        for (ReservationLine line : inLockOrder(reservation)) {
            StockLevel stock = lockLevelFor(reservation, line);

            if (stock.getOnHand() < line.getQuantity()) {
                // Should be unreachable: the quantity was reserved, and reserved never exceeds
                // on_hand. Checked anyway, because the alternative to a clear failure here is a
                // negative balance that surfaces days later in a stock report.
                throw new InsufficientStockException(
                        line.getItemId(),
                        reservation.getLocationId(),
                        line.getQuantity(),
                        stock.getOnHand());
            }

            stock.consume(line.getQuantity());
            levels.save(stock);

            StockMovement movement = new StockMovement();
            movement.setItemId(line.getItemId());
            movement.setLocationId(reservation.getLocationId());
            movement.setQtyDelta(-line.getQuantity());
            movement.setMovementType(StockMovementType.FULFILMENT);
            movement.setReferenceType(referenceType);
            movement.setReferenceId(referenceId);
            movement.setCreatedBy(actorId);
            movements.save(movement);
        }

        reservation.close(Reservation.CONSUMED, "fulfilled");
        Reservation saved = reservations.save(reservation);

        AuditContext.record(
                reservationId,
                Map.of("status", Reservation.ACTIVE),
                Map.of(
                        "status", Reservation.CONSUMED,
                        "referenceType", referenceType == null ? "" : referenceType,
                        "referenceId", referenceId == null ? "" : referenceId));

        log.info("Reservation {} consumed for {} {}", reservationId, referenceType, referenceId);
        return toView(saved);
    }

    // ------------------------------------------------------------------ shared mechanics

    private Reservation lockActive(UUID reservationId) {
        Reservation reservation =
                reservations
                        .lockForUpdate(reservationId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "RESERVATION_NOT_FOUND",
                                                "No reservation with that id."));

        if (!reservation.isActive()) {
            ConflictException conflict =
                    new ConflictException(
                            "RESERVATION_NOT_ACTIVE",
                            "That reservation has already been closed.");
            conflict.with("reservationStatus", reservation.getStatus());
            throw conflict;
        }
        return reservation;
    }

    /**
     * Sorted for the same reason reserving is: a release, a fulfilment and a reserve running at
     * once must not take the same rows in opposite orders.
     */
    private List<ReservationLine> inLockOrder(Reservation reservation) {
        return reservation.getLines().stream()
                .sorted((a, b) -> a.getItemId().compareTo(b.getItemId()))
                .toList();
    }

    private StockLevel lockLevelFor(Reservation reservation, ReservationLine line) {
        return levels.lockForUpdate(line.getItemId(), reservation.getLocationId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "stock_level row vanished for a live reservation: "
                                                + line.getItemId()));
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public ReservationView get(UUID id) {
        return toView(require(id));
    }

    @Transactional(readOnly = true)
    public List<ReservationView> list(boolean activeOnly) {
        return (activeOnly ? reservations.findAllActive() : reservations.findAllOrdered())
                .stream().map(ReservationService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<UUID> findExpiredIds(Instant asOf) {
        return reservations.findExpiredIds(asOf);
    }

    private Reservation require(UUID id) {
        return reservations
                .findById(id)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "RESERVATION_NOT_FOUND", "No reservation with that id."));
    }

    private static ReservationView toView(Reservation reservation) {
        return new ReservationView(
                reservation.getId(),
                reservation.getStatus(),
                reservation.getLocationId(),
                reservation.getReferenceType(),
                reservation.getReferenceId(),
                reservation.getCreatedAt(),
                reservation.getExpiresAt(),
                reservation.getReleasedAt(),
                reservation.getReleaseReason(),
                reservation.getLines().stream()
                        .map(line -> new ReservationLineView(line.getItemId(), line.getQuantity()))
                        .toList());
    }
}
