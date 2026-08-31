package com.democode.mlmsittu.commerce.internal.service;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrder;
import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrderLine;
import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrderStatus;
import com.democode.mlmsittu.commerce.internal.domain.Supplier;
import com.democode.mlmsittu.commerce.internal.repo.PurchaseOrderRepository;
import com.democode.mlmsittu.identity.api.UserDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import com.democode.mlmsittu.shared.notify.NotificationSender;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Purchase orders (development plan P2-07). */
@Service
public class PurchaseOrderService {

    private final PurchaseOrderRepository orders;
    private final SupplierService suppliers;
    private final SupplierPriceService supplierPrices;
    private final ItemCatalogue items;
    private final LocationDirectory locations;
    private final NotificationSender notifications;
    private final UserDirectory users;

    public PurchaseOrderService(
            PurchaseOrderRepository orders,
            SupplierService suppliers,
            SupplierPriceService supplierPrices,
            ItemCatalogue items,
            LocationDirectory locations,
            NotificationSender notifications,
            UserDirectory users) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.supplierPrices = supplierPrices;
        this.items = items;
        this.locations = locations;
        this.notifications = notifications;
        this.users = users;
    }

    /** One requested line, before it becomes a {@link PurchaseOrderLine}. */
    public record LineRequest(UUID itemId, int quantity, BigDecimal unitCost) {}

    @Transactional
    @Audited(action = "PURCHASE_ORDER_CREATED", entityType = "purchase_order", auditFailures = true)
    public PurchaseOrder create(
            UUID supplierId,
            UUID locationId,
            LocalDate expectedDate,
            String note,
            List<LineRequest> lineRequests,
            UUID actorId) {

        suppliers.requireSelectable(supplierId);
        LocationRef location = requireLocation(locationId);

        PurchaseOrder order = new PurchaseOrder();
        order.setPoNumber(String.format("PO-%06d", orders.nextNumber()));
        order.setSupplierId(supplierId);
        order.setLocationId(location.id());
        order.setExpectedDate(expectedDate);
        order.setNote(note);
        order.setCreatedBy(actorId);
        order.setStatusValue(PurchaseOrderStatus.DRAFT);
        order.setLines(buildLines(lineRequests, supplierId));

        PurchaseOrder saved = orders.save(order);
        AuditContext.record(saved.getId(), null, snapshot(saved));
        return saved;
    }

    /**
     * Replaces the lines of a draft order.
     *
     * <p>Refused once the order has been sent. The supplier is working from the document we
     * issued; changing our copy afterwards means the two disagree and every later receipt is
     * measured against a quantity nobody agreed to.
     */
    @Transactional
    @Audited(action = "PURCHASE_ORDER_LINES_CHANGED", entityType = "purchase_order", auditFailures = true)
    public PurchaseOrder replaceLines(UUID orderId, List<LineRequest> lineRequests) {
        PurchaseOrder order = require(orderId);
        Map<String, Object> before = snapshot(order);

        assertEditable(order);

        // Build first, so a bad request fails before anything is deleted.
        List<PurchaseOrderLine> replacement = buildLines(lineRequests, order.getSupplierId());

        // The flush in the middle is load-bearing. Hibernate orders inserts before deletes within
        // one flush, so re-quoting an item the order already has would insert the new line while
        // the old one is still there and hit uq_po_line_item — which is the normal edit, not an
        // edge case. Flushing the removals first makes the collision impossible.
        order.getLines().clear();
        orders.saveAndFlush(order);

        order.getLines().addAll(replacement);

        PurchaseOrder saved = orders.save(order);
        AuditContext.record(orderId, before, snapshot(saved));
        return saved;
    }

    /**
     * Issues the order to the supplier by email, and freezes it.
     *
     * <p>The send is not decorative — a supplier with no address on file is <b>refused</b>, with
     * {@code SUPPLIER_HAS_NO_EMAIL}. Flipping the status while the document went nowhere is the
     * worse outcome by a distance: procurement believes the order is with the supplier, the
     * warehouse waits for goods, and nothing looks wrong anywhere on the screen.
     *
     * <p>The named contact address wins over the company one. That is the person who chases the
     * order; the company address is usually accounts.
     *
     * <p>The send happens inside the transaction, so a database failure just after it leaves the
     * supplier holding an order we have no record of sending. Phase 7 outbox (§8.3) is what fixes
     * that properly; until then the window is one statement wide, and the alternative — sending
     * after commit, where a failed send leaves the order marked as sent — is worse.
     */
    @Transactional
    @Audited(action = "PURCHASE_ORDER_SENT", entityType = "purchase_order", auditFailures = true)
    public PurchaseOrder send(UUID orderId) {
        PurchaseOrder order = require(orderId);
        Map<String, Object> before = snapshot(order);

        if (order.statusValue() != PurchaseOrderStatus.DRAFT) {
            ConflictException conflict =
                    new ConflictException(
                            "PURCHASE_ORDER_NOT_DRAFT", "Only a draft order can be sent.");
            conflict.with("orderStatus", order.getStatus());
            throw conflict;
        }
        if (order.getLines().isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PURCHASE_ORDER_EMPTY",
                    "An order needs at least one line before it can be sent.");
        }

        Supplier supplier = suppliers.get(order.getSupplierId());
        String address = addressFor(supplier);

        Map<UUID, ItemRef> named =
                items.findAllById(
                        order.getLines().stream().map(PurchaseOrderLine::getItemId).toList());

        notifications.sendEmail(
                address,
                PurchaseOrderDocument.subject(order),
                PurchaseOrderDocument.body(order, supplier, named));

        order.setStatusValue(PurchaseOrderStatus.SENT);
        order.setSentAt(Instant.now());
        order.setSentToEmail(address);

        PurchaseOrder saved = orders.save(order);
        AuditContext.record(orderId, before, snapshot(saved));
        return saved;
    }

    /**
     * Records that the delivery physically turned up, on the word of a named person.
     *
     * <p>The caller types their own name and it is checked against their account. That check is
     * doing less than it looks — anybody signed in knows their own name — and it is not meant to
     * do more. It is a signature: a deliberate act that makes "who says these goods arrived" a
     * question with an answer, and stops a confirmation being one accidental click.
     *
     * <p>Any signed-in role may confirm. The client was explicit about that: whoever is at the
     * door when the lorry comes signs for it.
     *
     * <p>No stock moves here. That happens when the goods are put into a store.
     */
    @Transactional
    @Audited(action = "PURCHASE_ORDER_ARRIVED", entityType = "purchase_order", auditFailures = true)
    public PurchaseOrder confirmArrival(UUID orderId, String attestedName, UUID actorId) {
        PurchaseOrder order = require(orderId);
        Map<String, Object> before = snapshot(order);

        if (!order.statusValue().canConfirmArrival()) {
            ConflictException conflict =
                    new ConflictException(
                            "PURCHASE_ORDER_NOT_AWAITING_ARRIVAL",
                            order.statusValue() == PurchaseOrderStatus.DRAFT
                                    ? "This order has not been sent to the supplier yet."
                                    : "This order is not waiting to be confirmed as arrived.");
            conflict.with("orderStatus", order.getStatus());
            throw conflict;
        }

        String expected =
                users.findById(actorId)
                        .map(UserDirectory.UserRef::fullName)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "USER_NOT_FOUND",
                                                "The signed-in account no longer exists."));

        // Case and surrounding spaces are forgiven; the letters are not. Somebody typing their own
        // name should not be defeated by a capital.
        if (attestedName == null || !attestedName.trim().equalsIgnoreCase(expected.trim())) {
            ApiException mismatch =
                    new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "ATTESTED_NAME_MISMATCH",
                            "Type your own name exactly as it appears on your account.");
            // The expected name is echoed back on purpose: it is the caller own name, which they
            // already know, and without it the error is impossible to act on.
            mismatch.with("expectedName", expected);
            throw mismatch;
        }

        order.setStatusValue(PurchaseOrderStatus.ARRIVED);
        order.setArrivedAt(Instant.now());
        order.setArrivedBy(actorId);
        order.setArrivalAttestedName(expected);

        PurchaseOrder saved = orders.save(order);
        AuditContext.record(orderId, before, snapshot(saved));
        return saved;
    }

    @Transactional
    @Audited(action = "PURCHASE_ORDER_CANCELLED", entityType = "purchase_order", auditFailures = true)
    public PurchaseOrder cancel(UUID orderId) {
        PurchaseOrder order = require(orderId);
        Map<String, Object> before = snapshot(order);

        if (order.anythingReceived()) {
            // Stock has already moved against this order. Cancelling would leave movements
            // pointing at a document that claims nothing was ever ordered.
            throw new ConflictException(
                    "PURCHASE_ORDER_PARTIALLY_RECEIVED",
                    "Stock has already been received against this order; it cannot be cancelled.");
        }
        if (order.statusValue() == PurchaseOrderStatus.CANCELLED) {
            throw new ConflictException(
                    "PURCHASE_ORDER_ALREADY_CANCELLED", "That order is already cancelled.");
        }
        if (order.statusValue() == PurchaseOrderStatus.ARRIVED) {
            // Somebody has signed for these goods. They are in the building; cancelling the order
            // would leave them there with no document saying why.
            ConflictException conflict =
                    new ConflictException(
                            "PURCHASE_ORDER_ALREADY_ARRIVED",
                            "This delivery is confirmed as arrived; put it into a store instead.");
            conflict.with("orderStatus", order.getStatus());
            throw conflict;
        }

        order.setStatusValue(PurchaseOrderStatus.CANCELLED);
        order.setClosedAt(Instant.now());

        PurchaseOrder saved = orders.save(order);
        AuditContext.record(orderId, before, snapshot(saved));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrder> list() {
        return orders.findAllOrdered();
    }

    /**
     * Orders whose goods are in the building but not yet on a shelf — the receiving screen first
     * tab.
     *
     * <p>Filtered here rather than in the browser, so the answer does not depend on how many
     * orders the client happened to fetch.
     */
    @Transactional(readOnly = true)
    public List<PurchaseOrder> awaitingStoring() {
        return orders.findAllOrdered().stream()
                .filter(order -> order.statusValue().isOpenForReceipt())
                .toList();
    }

    @Transactional(readOnly = true)
    public PurchaseOrder get(UUID id) {
        return require(id);
    }

    // ------------------------------------------------------------------ helpers

    private void assertEditable(PurchaseOrder order) {
        if (order.statusValue() != PurchaseOrderStatus.DRAFT) {
            ConflictException conflict =
                    new ConflictException(
                            "PURCHASE_ORDER_NOT_EDITABLE",
                            "An order can only be edited while it is a draft.");
            // Named orderStatus, not status: ProblemDetail already owns "status" for the HTTP
            // code, and a duplicate key in the body is ambiguous to every client that reads it.
            conflict.with("orderStatus", order.getStatus());
            throw conflict;
        }
    }

    /**
     * @param supplierId whose quotes to price the lines from, when the caller gave no explicit cost
     */
    private List<PurchaseOrderLine> buildLines(List<LineRequest> requests, UUID supplierId) {
        if (requests == null || requests.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PURCHASE_ORDER_EMPTY",
                    "An order needs at least one line.");
        }

        Set<UUID> seen = new LinkedHashSet<>();
        for (LineRequest request : requests) {
            if (!seen.add(request.itemId())) {
                throw new ConflictException(
                        "DUPLICATE_ORDER_LINE",
                        "The same item appears twice. Combine it into one line.");
            }
        }

        Map<UUID, ItemRef> known = items.findAllById(seen);

        // One lookup for the whole order rather than one per line — the difference only shows up
        // once somebody builds a realistic order, which is exactly when it is hardest to notice.
        Map<UUID, BigDecimal> quoted = supplierPrices.quotedBy(supplierId, List.copyOf(seen));

        List<PurchaseOrderLine> lines = new ArrayList<>();
        int lineNo = 1;

        for (LineRequest request : requests) {
            ItemRef item = known.get(request.itemId());
            if (item == null) {
                NotFoundException notFound =
                        new NotFoundException("ITEM_NOT_FOUND", "No item with that id.");
                notFound.with("itemId", request.itemId());
                throw notFound;
            }
            if (!item.active()) {
                throw new ConflictException(
                        "ITEM_INACTIVE", "A deactivated item cannot be ordered: " + item.sku());
            }

            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setItemId(item.id());
            line.setLineNo(lineNo++);
            line.setQuantityOrdered(request.quantity());
            // Three sources, most specific first: what the buyer typed, what this supplier quotes
            // for this item, then the catalogue's own cost. The middle one is the point of
            // per-supplier pricing — ordering the same goods from a cheaper supplier should not
            // require anybody to remember their price.
            line.setUnitCost(
                    request.unitCost() != null
                            ? request.unitCost()
                            : quoted.getOrDefault(item.id(), item.unitCost()));
            lines.add(line);
        }
        return lines;
    }

    /** The named contact first, the company switchboard second, a refusal third. */
    private String addressFor(Supplier supplier) {
        String contact = supplier.getContactEmail();
        if (contact != null && !contact.isBlank()) {
            return contact.trim();
        }
        String company = supplier.getEmail();
        if (company != null && !company.isBlank()) {
            return company.trim();
        }
        ConflictException conflict =
                new ConflictException(
                        "SUPPLIER_HAS_NO_EMAIL",
                        "This supplier has no email address, so the order cannot be sent."
                                + " Add one on the supplier first.");
        conflict.with("supplierId", supplier.getId());
        conflict.with("supplierName", supplier.getName());
        throw conflict;
    }

    private LocationRef requireLocation(UUID locationId) {
        if (locationId == null) {
            return locations.defaultLocation();
        }
        return locations
                .findById(locationId)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "LOCATION_NOT_FOUND", "No location with that id."));
    }

    private PurchaseOrder require(UUID id) {
        return orders.findById(id)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "PURCHASE_ORDER_NOT_FOUND",
                                        "No purchase order with that id."));
    }

    private Map<String, Object> snapshot(PurchaseOrder order) {
        return Map.of(
                "poNumber", order.getPoNumber(),
                "status", order.getStatus(),
                "supplierId", order.getSupplierId(),
                "lineCount", order.getLines().size(),
                "total", order.total());
    }
}
