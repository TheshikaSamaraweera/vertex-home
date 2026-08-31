package com.democode.mlmsittu.commerce.internal.service;

import com.democode.mlmsittu.commerce.internal.domain.Invoice;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrder;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrderStatus;
import com.democode.mlmsittu.inventory.api.StockReservations;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ConflictException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releasing the goods (development plan P5-08, P5-10).
 *
 * <p>Three things happen, or none of them: the held stock is consumed, the order becomes
 * {@code fulfilled}, and the invoice is issued. One transaction, because an invoice for goods that
 * never left is a false accounting record and stock that left without an invoice is a missing one.
 */
@Service
public class FulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentService.class);

    private final SalesOrderService orders;
    private final StockReservations reservations;
    private final InvoiceService invoices;

    public FulfilmentService(
            SalesOrderService orders, StockReservations reservations, InvoiceService invoices) {
        this.orders = orders;
        this.reservations = reservations;
        this.invoices = invoices;
    }

    public record FulfilmentResult(SalesOrder order, Invoice invoice) {}

    @Transactional
    @Audited(action = "SALES_ORDER_FULFILLED", entityType = "sales_order", auditFailures = true)
    public FulfilmentResult fulfil(UUID orderId, UUID actorId) {
        SalesOrder order = orders.lock(orderId);
        Map<String, Object> before = SalesOrderService.snapshot(order);

        if (order.statusValue() != SalesOrderStatus.PAID) {
            ConflictException conflict =
                    new ConflictException(
                            "ORDER_NOT_PAID",
                            "Goods are released only after a payment has been verified.");
            conflict.with("orderStatus", order.getStatus());
            throw conflict;
        }
        if (order.getReservationId() == null) {
            // A paid order with nothing held is a contradiction — the reservation is taken at
            // creation and only released by rejection or cancellation, neither of which leaves the
            // order paid. Refusing loudly beats shipping stock the books never accounted for.
            throw new ConflictException(
                    "ORDER_HOLDS_NO_STOCK",
                    "That order is not holding any stock. It cannot be fulfilled as it stands.");
        }

        // on_hand and reserved fall together here, and the negative movements are appended in this
        // same transaction — see StockReservations#consume for why it is not release-then-post.
        reservations.consume(
                order.getReservationId(), SalesOrderService.REFERENCE_TYPE, order.getId(), actorId);

        order.setStatusValue(SalesOrderStatus.FULFILLED);
        order.setFulfilledAt(Instant.now());
        SalesOrder saved = orders.save(order);

        Invoice invoice = invoices.issueFor(saved, actorId);

        AuditContext.record(orderId, before, SalesOrderService.snapshot(saved));
        log.info(
                "Order {} fulfilled — invoice {} issued",
                saved.getOrderNumber(),
                invoice.getInvoiceNumber());

        return new FulfilmentResult(saved, invoice);
    }
}
