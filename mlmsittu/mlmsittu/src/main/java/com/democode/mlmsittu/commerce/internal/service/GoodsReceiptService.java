package com.democode.mlmsittu.commerce.internal.service;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.commerce.internal.domain.GoodsReceipt;
import com.democode.mlmsittu.commerce.internal.domain.GoodsReceiptLine;
import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrder;
import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrderLine;
import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrderStatus;
import com.democode.mlmsittu.commerce.internal.repo.GoodsReceiptRepository;
import com.democode.mlmsittu.commerce.internal.repo.PurchaseOrderRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockMovementType;
import com.democode.mlmsittu.inventory.api.StockPosting;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Putting a delivery into stores (development plan P2-08).
 *
 * <p>This is the step where stock actually moves, and the only one. Confirming that a lorry
 * arrived changes no quantity anywhere; the numbers change here, when somebody says which store
 * the goods went into. The client's warehouse works that way — things sit on the receiving bay for
 * hours — and pretending otherwise made the system claim stock was on a shelf that was still in a
 * box by the door.
 *
 * <p>Two ways in, and they converge:
 *
 * <ul>
 *   <li>{@link #storeFromOrder} — against a purchase order that has been confirmed as arrived.
 *       Receipt lines close order lines, and the order's status follows.
 *   <li>{@link #storeManual} — goods with no order behind them. A supplier and a store are still
 *       required; there is simply no order line to close.
 * </ul>
 *
 * <p>The whole operation is one transaction: the receipt document, the updated received
 * quantities, the order's new status and the stock movements all commit together or not at all. A
 * partial failure here is how a warehouse ends up with stock on the shelf that the system says
 * never arrived.
 */
@Service
public class GoodsReceiptService {

    private final GoodsReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final SupplierService suppliers;
    private final ItemCatalogue items;
    private final LocationDirectory locations;
    private final StockLedger ledger;

    public GoodsReceiptService(
            GoodsReceiptRepository receipts,
            PurchaseOrderRepository orders,
            SupplierService suppliers,
            ItemCatalogue items,
            LocationDirectory locations,
            StockLedger ledger) {
        this.receipts = receipts;
        this.orders = orders;
        this.suppliers = suppliers;
        this.items = items;
        this.locations = locations;
        this.ledger = ledger;
    }

    /**
     * @param purchaseOrderLineId the line being delivered against, not the item
     * @param locationId which store this line goes into; null falls back to the receipt's store
     */
    public record ReceiptLineRequest(UUID purchaseOrderLineId, int quantity, UUID locationId) {}

    /**
     * @param locationId which store this line goes into; null falls back to the receipt's store
     */
    public record ManualLineRequest(UUID itemId, int quantity, UUID locationId) {}

    // ------------------------------------------------------------------ against an order

    /**
     * @param locationId the store for lines that do not name their own; null means the order's
     * @throws ConflictException {@code PURCHASE_ORDER_NOT_RECEIVABLE} if nobody has confirmed the
     *     delivery arrived — an unconfirmed order has nothing to put away
     */
    @Transactional
    @Audited(action = "GOODS_RECEIVED", entityType = "goods_receipt", auditFailures = true)
    public GoodsReceipt storeFromOrder(
            UUID purchaseOrderId,
            UUID locationId,
            List<ReceiptLineRequest> lineRequests,
            String supplierNote,
            UUID actorId) {

        requireLines(lineRequests == null ? 0 : lineRequests.size());

        // Locked for the duration: two deliveries booked at once would otherwise both read the
        // same received quantities and the second would overwrite the first.
        PurchaseOrder order =
                orders.lockForUpdate(purchaseOrderId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "PURCHASE_ORDER_NOT_FOUND",
                                                "No purchase order with that id."));

        if (!order.statusValue().isOpenForReceipt()) {
            ConflictException conflict =
                    new ConflictException(
                            "PURCHASE_ORDER_NOT_RECEIVABLE",
                            order.statusValue() == PurchaseOrderStatus.SENT
                                    ? "Confirm this delivery has arrived before putting it into a store."
                                    : "This order is not open for receipt.");
            conflict.with("orderStatus", order.getStatus());
            throw conflict;
        }

        UUID fallback = resolveLocation(locationId != null ? locationId : order.getLocationId());

        Map<UUID, PurchaseOrderLine> linesById = new LinkedHashMap<>();
        order.getLines().forEach(line -> linesById.put(line.getId(), line));

        // Combine repeats first, so two entries for the same PO line are checked against the total
        // rather than each passing on its own and jointly over-receiving. The store is taken from
        // the first entry that names one — splitting one order line across two stores is a second
        // receipt, not a second row.
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        Map<UUID, UUID> lineLocations = new LinkedHashMap<>();
        Set<UUID> unknown = new LinkedHashSet<>();

        for (ReceiptLineRequest request : lineRequests) {
            if (!linesById.containsKey(request.purchaseOrderLineId())) {
                unknown.add(request.purchaseOrderLineId());
                continue;
            }
            requirePositive(request.quantity());
            quantities.merge(request.purchaseOrderLineId(), request.quantity(), Integer::sum);
            if (request.locationId() != null) {
                lineLocations.putIfAbsent(
                        request.purchaseOrderLineId(), resolveLocation(request.locationId()));
            }
        }

        if (!unknown.isEmpty()) {
            NotFoundException notFound =
                    new NotFoundException(
                            "PURCHASE_ORDER_LINE_NOT_FOUND",
                            "One or more lines do not belong to this order.");
            notFound.with("purchaseOrderLineIds", unknown);
            throw notFound;
        }

        GoodsReceipt receipt = newReceipt(fallback, supplierNote, actorId);
        receipt.setPurchaseOrderId(order.getId());
        receipt.setSupplierId(order.getSupplierId());
        receipt.setSource("purchase_order");

        for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
            PurchaseOrderLine line = linesById.get(entry.getKey());
            int quantity = entry.getValue();

            if (quantity > line.outstanding()) {
                // The database CHECK would also refuse this. Catching it here means the caller
                // gets the numbers rather than a constraint violation.
                ConflictException conflict =
                        new ConflictException(
                                "OVER_RECEIPT",
                                "That is more than the order still has outstanding.");
                conflict.with("purchaseOrderLineId", line.getId());
                conflict.with("itemId", line.getItemId());
                conflict.with("ordered", line.getQuantityOrdered());
                conflict.with("alreadyReceived", line.getQuantityReceived());
                conflict.with("outstanding", line.outstanding());
                conflict.with("attempted", quantity);
                throw conflict;
            }

            line.receive(quantity);

            GoodsReceiptLine receiptLine = new GoodsReceiptLine();
            receiptLine.setPurchaseOrderLineId(line.getId());
            receiptLine.setItemId(line.getItemId());
            receiptLine.setLocationId(lineLocations.getOrDefault(line.getId(), fallback));
            receiptLine.setQuantityReceived(quantity);
            receipt.getLines().add(receiptLine);
        }

        GoodsReceipt saved =
                post(
                        receipt,
                        "Received on " + order.getPoNumber() + " from " + supplierName(order.getSupplierId()),
                        actorId);

        order.setStatusValue(
                order.fullyReceived()
                        ? PurchaseOrderStatus.RECEIVED
                        : PurchaseOrderStatus.PARTIALLY_RECEIVED);
        if (order.fullyReceived()) {
            order.setClosedAt(Instant.now());
        }
        orders.save(order);

        AuditContext.record(
                saved.getId(),
                null,
                Map.of(
                        "receiptNumber", saved.getReceiptNumber(),
                        "source", "purchase_order",
                        "purchaseOrderId", order.getId(),
                        "poNumber", order.getPoNumber(),
                        "lines", quantities.size(),
                        "orderStatusAfter", order.getStatus()));

        return saved;
    }

    // ------------------------------------------------------------------ with no order behind it

    /**
     * Books goods that arrived without a purchase order.
     *
     * <p>Every bit as real as an ordered delivery, and treated the same by the ledger: same
     * receipt document, same movement type, same traceability. The supplier and the store stay
     * mandatory — stock that appears from nowhere, in no particular place, is exactly what the
     * ledger exists to make impossible.
     */
    @Transactional
    @Audited(action = "GOODS_RECEIVED_MANUAL", entityType = "goods_receipt", auditFailures = true)
    public GoodsReceipt storeManual(
            UUID supplierId,
            UUID locationId,
            List<ManualLineRequest> lineRequests,
            String supplierNote,
            UUID actorId) {

        requireLines(lineRequests == null ? 0 : lineRequests.size());
        suppliers.requireSelectable(supplierId);

        UUID fallback = resolveLocation(locationId);

        // Same item into the same store twice is one line. Into two different stores it is two,
        // which is the point of holding the store on the line.
        Map<List<UUID>, Integer> combined = new LinkedHashMap<>();
        for (ManualLineRequest request : lineRequests) {
            if (request.itemId() == null) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST, "REQUIRED", "Every line needs an item.");
            }
            requirePositive(request.quantity());
            UUID store =
                    request.locationId() != null ? resolveLocation(request.locationId()) : fallback;
            combined.merge(List.of(request.itemId(), store), request.quantity(), Integer::sum);
        }

        Set<UUID> itemIds = new LinkedHashSet<>();
        combined.keySet().forEach(key -> itemIds.add(key.get(0)));
        Map<UUID, ItemRef> known = items.findAllById(itemIds);

        GoodsReceipt receipt = newReceipt(fallback, supplierNote, actorId);
        receipt.setPurchaseOrderId(null);
        receipt.setSupplierId(supplierId);
        receipt.setSource("manual");

        for (Map.Entry<List<UUID>, Integer> entry : combined.entrySet()) {
            UUID itemId = entry.getKey().get(0);
            ItemRef item = known.get(itemId);
            if (item == null) {
                NotFoundException notFound =
                        new NotFoundException("ITEM_NOT_FOUND", "No item with that id.");
                notFound.with("itemId", itemId);
                throw notFound;
            }
            if (!item.active()) {
                // Receiving into a deactivated item would put stock somewhere nobody can sell it
                // from, and the quantity would sit there unexplained.
                ConflictException conflict =
                        new ConflictException(
                                "ITEM_INACTIVE",
                                "A deactivated item cannot be received: " + item.sku());
                conflict.with("itemId", itemId);
                throw conflict;
            }

            GoodsReceiptLine line = new GoodsReceiptLine();
            line.setPurchaseOrderLineId(null);
            line.setItemId(itemId);
            line.setLocationId(entry.getKey().get(1));
            line.setQuantityReceived(entry.getValue());
            receipt.getLines().add(line);
        }

        GoodsReceipt saved =
                post(receipt, "Added by hand from " + supplierName(supplierId), actorId);

        AuditContext.record(
                saved.getId(),
                null,
                Map.of(
                        "receiptNumber", saved.getReceiptNumber(),
                        "source", "manual",
                        "supplierId", supplierId,
                        "lines", combined.size()));

        return saved;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public GoodsReceipt get(UUID id) {
        return receipts
                .findById(id)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "GOODS_RECEIPT_NOT_FOUND", "No receipt with that id."));
    }

    @Transactional(readOnly = true)
    public List<GoodsReceipt> listForOrder(UUID purchaseOrderId) {
        return receipts.findByPurchaseOrder(purchaseOrderId);
    }

    /** Everything put into a store, newest first — the receiving screen's second tab. */
    @Transactional(readOnly = true)
    public List<GoodsReceipt> list() {
        return receipts.findAllNewestFirst();
    }

    // ------------------------------------------------------------------ helpers

    private GoodsReceipt newReceipt(UUID locationId, String supplierNote, UUID actorId) {
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setReceiptNumber(String.format("GRN-%06d", receipts.nextNumber()));
        receipt.setLocationId(locationId);
        receipt.setSupplierNote(supplierNote);
        receipt.setReceivedAt(Instant.now());
        receipt.setCreatedBy(actorId);
        return receipt;
    }

    /**
     * Saves the receipt, then posts one movement per line against it.
     *
     * <p>The order matters. The receipt id only exists after the insert, and every movement must
     * point back to it — a movement with no traceable cause is the thing the ledger exists to
     * prevent.
     */
    private GoodsReceipt post(GoodsReceipt receipt, String documentNote, UUID actorId) {
        GoodsReceipt saved = receipts.saveAndFlush(receipt);

        // Written into the movement, not resolved when somebody looks. The ledger is append-only
        // and its rows outlive the documents they point at: an order can be renumbered, a supplier
        // renamed, and a label computed today would then describe something that was never true.
        // What the note says is what was true when the stock moved.
        String note = trim(saved.getReceiptNumber() + " · " + documentNote);

        List<StockPosting> postings = new ArrayList<>();
        for (GoodsReceiptLine line : saved.getLines()) {
            postings.add(
                    new StockPosting(
                            line.getItemId(),
                            line.getLocationId(),
                            line.getQuantityReceived(),
                            StockMovementType.RECEIPT,
                            "goods_receipt",
                            saved.getId(),
                            null,
                            note,
                            actorId));
        }
        ledger.postAll(postings);
        return saved;
    }

    /** The column holds 500; a supplier with a very long name must not fail a delivery. */
    private String trim(String note) {
        return note.length() <= 500 ? note : note.substring(0, 497) + "…";
    }

    private String supplierName(UUID supplierId) {
        try {
            return suppliers.get(supplierId).getName();
        } catch (NotFoundException missing) {
            // Never expected — the supplier was validated moments ago. Losing the delivery over a
            // label would be absurd, so degrade the note rather than the posting.
            return "supplier " + supplierId;
        }
    }

    private UUID resolveLocation(UUID locationId) {
        if (locationId == null) {
            return locations.defaultLocation().id();
        }
        return locations
                .findById(locationId)
                .orElseThrow(
                        () -> {
                            NotFoundException notFound =
                                    new NotFoundException(
                                            "LOCATION_NOT_FOUND", "No store with that id.");
                            notFound.with("locationId", locationId);
                            return notFound;
                        })
                .id();
    }

    private void requireLines(int count) {
        if (count == 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "RECEIPT_EMPTY",
                    "A goods receipt needs at least one line.");
        }
    }

    private void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_RECEIPT_QUANTITY",
                    "Received quantity must be greater than zero.");
        }
    }
}
