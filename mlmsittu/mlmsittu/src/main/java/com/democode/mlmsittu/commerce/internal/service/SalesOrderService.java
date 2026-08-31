package com.democode.mlmsittu.commerce.internal.service;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.catalogue.api.ItemSetCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemSetRef;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrder;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrderLine;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrderStatus;
import com.democode.mlmsittu.commerce.internal.repo.SalesOrderRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.inventory.api.ReservationRequestLine;
import com.democode.mlmsittu.inventory.api.ReservationView;
import com.democode.mlmsittu.inventory.api.StockReservations;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales orders (development plan P5-02, P5-03).
 *
 * <p>Creating an order holds the stock immediately. That is the whole reason the reservation
 * machinery from Phase 3 exists: an order that does not hold its stock is a promise the warehouse
 * has not agreed to, and the customer finds out at the worst possible moment.
 */
@Service
public class SalesOrderService {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderService.class);

    /** What the reservation is held for, written into {@code reservation.reference_type}. */
    static final String REFERENCE_TYPE = "sales_order";

    private final SalesOrderRepository orders;
    private final CustomerService customerService;
    private final ItemCatalogue items;
    private final ItemSetCatalogue sets;
    private final LocationDirectory locations;
    private final StockReservations reservations;

    public SalesOrderService(
            SalesOrderRepository orders,
            CustomerService customerService,
            ItemCatalogue items,
            ItemSetCatalogue sets,
            LocationDirectory locations,
            StockReservations reservations) {
        this.orders = orders;
        this.customerService = customerService;
        this.items = items;
        this.sets = sets;
        this.locations = locations;
        this.reservations = reservations;
    }

    /** One requested line. Exactly one of {@code itemId} and {@code setId} is set. */
    public record LineRequest(UUID itemId, UUID setId, int quantity, BigDecimal unitPrice) {}

    public record OrderRequest(
            UUID customerId,
            UUID locationId,
            List<LineRequest> lines,
            BigDecimal discount,
            String note,
            String idempotencyKey) {}

    // ==============================================================================
    // Creation
    // ==============================================================================

    @Transactional
    @Audited(action = "SALES_ORDER_CREATED", entityType = "sales_order", auditFailures = true)
    public SalesOrder create(OrderRequest request, UUID actorId) {

        // Blank is treated as absent, so a client sending an empty header does not store a key
        // that can never be matched again.
        String key =
                request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                        ? null
                        : request.idempotencyKey().trim();
        String fingerprint = fingerprintOf(request);

        Optional<SalesOrder> replay = findIdempotentReplay(key, fingerprint, actorId);
        if (replay.isPresent()) {
            return replay.get();
        }

        customerService.requireSelectable(request.customerId());
        LocationRef location = requireLocation(request.locationId());

        SalesOrder order = new SalesOrder();
        order.setOrderNumber(String.format("SO-%06d", orders.nextNumber()));
        order.setCustomerId(request.customerId());
        order.setLocationId(location.id());
        order.setCreatedBy(actorId);
        order.setNote(request.note());
        order.setDiscount(request.discount() == null ? BigDecimal.ZERO : request.discount());
        order.setStatusValue(SalesOrderStatus.AWAITING_PAYMENT);
        order.setIdempotencyKey(key);
        order.setRequestFingerprint(key == null ? null : fingerprint);
        order.getLines().addAll(buildLines(request.lines()));
        order.recalculateTotals();

        SalesOrder saved = persist(order, actorId);

        // Reserved after the order exists, so the reservation can point back at it. Same
        // transaction: if reserving fails on any component, the order goes with it and there is no
        // half-made sale to clean up.
        ReservationView reservation = reserveFor(saved, actorId);
        saved.setReservationId(reservation.id());

        SalesOrder withStock = orders.save(saved);
        AuditContext.record(withStock.getId(), null, snapshot(withStock));

        log.info(
                "Sales order {} created for customer {}, reservation {}",
                withStock.getOrderNumber(),
                withStock.getCustomerId(),
                reservation.id());
        return withStock;
    }

    /**
     * P5-03 · idempotency.
     *
     * <p>Two different failures hide behind one retry. A network timeout means the caller does not
     * know whether the order was created, and sending the identical request again must return the
     * first order rather than making a second. A <em>different</em> request under the same key is
     * not a retry at all — it is a bug in the caller, and answering it with somebody else's order
     * would hide that bug behind a plausible-looking success. So the request is fingerprinted and
     * the two cases are answered differently.
     */
    private Optional<SalesOrder> findIdempotentReplay(
            String key, String fingerprint, UUID actorId) {

        if (key == null) {
            return Optional.empty();
        }

        Optional<SalesOrder> existing = orders.findByCreatedByAndIdempotencyKey(actorId, key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        SalesOrder order = existing.get();
        if (!fingerprint.equals(order.getRequestFingerprint())) {
            ConflictException conflict =
                    new ConflictException(
                            "IDEMPOTENCY_KEY_REUSED",
                            "That idempotency key was already used for a different order. Use a"
                                + " new key.");
            conflict.with("existingOrderNumber", order.getOrderNumber());
            throw conflict;
        }

        log.info("Idempotent replay of {} — returning the original order", order.getOrderNumber());
        return Optional.of(order);
    }

    /**
     * A stable hash of everything that makes the order what it is.
     *
     * <p>Lines are sorted before hashing, so the same order described in a different sequence is
     * recognised as the same order. The note is excluded on purpose — it is free text a caller may
     * legitimately reword between retries, and treating a typo fix as a different request would
     * turn a safe retry into a 409.
     */
    private String fingerprintOf(OrderRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(request.customerId()).append('|');
        canonical.append(request.locationId()).append('|');
        canonical.append(request.discount() == null ? "0" : request.discount().stripTrailingZeros());

        request.lines().stream()
                .map(
                        line ->
                                line.itemId()
                                        + ":"
                                        + line.setId()
                                        + ":"
                                        + line.quantity()
                                        + ":"
                                        + (line.unitPrice() == null
                                                ? ""
                                                : line.unitPrice().stripTrailingZeros()))
                .sorted()
                .forEach(part -> canonical.append('|').append(part));

        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private SalesOrder persist(SalesOrder order, UUID actorId) {
        try {
            return orders.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            // The pre-check above missed it, which means a second request with the same key
            // committed in the moment between the two. The insert blocked on the unique index
            // until that one landed, so the order does exist now — but this transaction is
            // already poisoned and cannot read it. Asking the caller to retry is the honest
            // answer; the retry takes the pre-check path and gets the original order.
            RuntimeException specific =
                    ConflictException.ifConstraintIs(
                            e,
                            "idx_sales_order_idempotency",
                            "IDEMPOTENT_REQUEST_IN_FLIGHT",
                            "An identical request is still being processed. Retry in a moment.");
            if (specific != e) {
                log.info("Concurrent duplicate of idempotency key from actor {}", actorId);
            }
            throw specific;
        }
    }

    // ==============================================================================
    // Lines
    // ==============================================================================

    private List<SalesOrderLine> buildLines(List<LineRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "SALES_ORDER_EMPTY",
                    "An order needs at least one line.");
        }

        Set<String> seen = new LinkedHashSet<>();
        List<SalesOrderLine> lines = new ArrayList<>();
        int lineNo = 1;

        for (LineRequest request : requests) {
            boolean hasItem = request.itemId() != null;
            boolean hasSet = request.setId() != null;
            if (hasItem == hasSet) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_ORDER_LINE",
                        "Each line must name either an item or a set, not both and not neither.");
            }
            if (request.quantity() <= 0) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_QUANTITY",
                        "Quantity must be greater than zero.");
            }
            if (!seen.add(hasItem ? "item:" + request.itemId() : "set:" + request.setId())) {
                throw new ConflictException(
                        "DUPLICATE_ORDER_LINE",
                        "The same product appears twice. Combine it into one line.");
            }

            SalesOrderLine line = new SalesOrderLine();
            line.setLineNo(lineNo++);
            line.setQuantity(request.quantity());

            if (hasItem) {
                ItemRef item =
                        items.findById(request.itemId())
                                .orElseThrow(
                                        () -> {
                                            NotFoundException notFound =
                                                    new NotFoundException(
                                                            "ITEM_NOT_FOUND",
                                                            "No item with that id.");
                                            notFound.with("itemId", request.itemId());
                                            return notFound;
                                        });
                if (!item.active()) {
                    throw new ConflictException(
                            "ITEM_INACTIVE", "A deactivated item cannot be sold: " + item.sku());
                }
                line.setItemId(item.id());
                line.setDescription(item.sku() + " · " + item.name());
                line.setUnitPrice(priceOr(request.unitPrice(), item.sellingPrice()));
            } else {
                ItemSetRef set =
                        sets.findById(request.setId())
                                .orElseThrow(
                                        () ->
                                                new NotFoundException(
                                                        "ITEM_SET_NOT_FOUND",
                                                        "No item set with that id."));
                if (!set.active()) {
                    throw new ConflictException(
                            "ITEM_SET_INACTIVE",
                            "That set is deactivated and cannot be sold: " + set.code());
                }
                line.setSetId(set.id());
                line.setDescription(set.code() + " · " + set.name());
                line.setUnitPrice(priceOr(request.unitPrice(), set.setPrice()));
            }

            line.recalculate();
            lines.add(line);
        }
        return lines;
    }

    /**
     * The catalogue price unless the seller overrode it.
     *
     * <p>An override is a negotiated price, which is a real thing in this business. It is copied
     * onto the line rather than referenced, so a later catalogue change never rewrites what was
     * charged.
     */
    private BigDecimal priceOr(BigDecimal override, BigDecimal catalogue) {
        BigDecimal price = override != null ? override : catalogue;
        if (price == null || price.signum() < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PRICE_REQUIRED",
                    "That product has no selling price. Set one, or give a price on the line.");
        }
        return price;
    }

    // ==============================================================================
    // Stock
    // ==============================================================================

    /**
     * Holds every component of every line.
     *
     * <p>Sets are handed over as sets: the reservation expands them, which is where the knowledge
     * of what a set contains belongs and where the component quantities are merged so two sets
     * sharing an item are checked against the combined requirement.
     */
    ReservationView reserveFor(SalesOrder order, UUID actorId) {
        List<ReservationRequestLine> lines =
                order.getLines().stream()
                        .map(
                                line ->
                                        new ReservationRequestLine(
                                                line.getItemId(), line.getSetId(), line.getQuantity()))
                        .toList();

        // No TTL. A sales order holds its stock until it is paid, fulfilled, rejected or
        // cancelled — a timer would quietly drop the hold on an order somebody has already paid
        // for by bank transfer, which can take a day to clear.
        return reservations.reserve(
                lines, order.getLocationId(), REFERENCE_TYPE, order.getId(), null, actorId);
    }

    // ==============================================================================
    // Cancellation
    // ==============================================================================

    @Transactional
    @Audited(action = "SALES_ORDER_CANCELLED", entityType = "sales_order", auditFailures = true)
    public SalesOrder cancel(UUID orderId, String reason, UUID actorId) {
        SalesOrder order = lock(orderId);
        Map<String, Object> before = snapshot(order);

        SalesOrderStatus status = order.statusValue();
        if (status == SalesOrderStatus.FULFILLED) {
            throw new ConflictException(
                    "SALES_ORDER_ALREADY_FULFILLED",
                    "The goods have left. Record a return instead of cancelling.");
        }
        if (status == SalesOrderStatus.CANCELLED) {
            throw new ConflictException(
                    "SALES_ORDER_ALREADY_CANCELLED", "That order is already cancelled.");
        }

        releaseHeldStock(order, reason == null ? "order cancelled" : reason);

        order.setStatusValue(SalesOrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());

        SalesOrder saved = orders.save(order);
        AuditContext.record(orderId, before, snapshot(saved));
        return saved;
    }

    /** Gives back whatever this order is holding, if anything. Safe to call twice. */
    void releaseHeldStock(SalesOrder order, String reason) {
        if (order.getReservationId() == null) {
            return;
        }
        ReservationView reservation = reservations.get(order.getReservationId());
        if (reservation.isActive()) {
            reservations.release(order.getReservationId(), reason);
        }
        order.setReservationId(null);
    }

    // ==============================================================================
    // Reads and shared helpers
    // ==============================================================================

    @Transactional(readOnly = true)
    public List<SalesOrder> list(UUID customerId, String status) {
        if (customerId != null) {
            return orders.findForCustomer(customerId);
        }
        if (status != null && !status.isBlank()) {
            return orders.findByStatus(SalesOrderStatus.fromDbValue(status).dbValue());
        }
        return orders.findAllOrdered();
    }

    @Transactional(readOnly = true)
    public SalesOrder get(UUID id) {
        return require(id);
    }

    SalesOrder lock(UUID id) {
        return orders.lockForUpdate(id).orElseThrow(SalesOrderService::notFound);
    }

    SalesOrder save(SalesOrder order) {
        return orders.save(order);
    }

    private SalesOrder require(UUID id) {
        return orders.findById(id).orElseThrow(SalesOrderService::notFound);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("SALES_ORDER_NOT_FOUND", "No sales order with that id.");
    }

    private LocationRef requireLocation(UUID locationId) {
        if (locationId == null) {
            return locations.defaultLocation();
        }
        return locations
                .findById(locationId)
                .orElseThrow(
                        () -> new NotFoundException("LOCATION_NOT_FOUND", "No location with that id."));
    }

    static Map<String, Object> snapshot(SalesOrder order) {
        return Map.of(
                "orderNumber", order.getOrderNumber(),
                // Named orderStatus, not status: ProblemDetail owns "status" for the HTTP code and
                // audit snapshots are read alongside error bodies often enough that the collision
                // is worth avoiding here too.
                "orderStatus", order.getStatus(),
                "customerId", order.getCustomerId(),
                "lineCount", order.getLines().size(),
                "total", order.getTotal());
    }
}
