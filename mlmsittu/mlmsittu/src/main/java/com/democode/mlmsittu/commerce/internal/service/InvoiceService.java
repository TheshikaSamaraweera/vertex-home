package com.democode.mlmsittu.commerce.internal.service;

import com.democode.mlmsittu.commerce.internal.domain.Invoice;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrder;
import com.democode.mlmsittu.commerce.internal.repo.InvoiceRepository;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice numbering without gaps (development plan P5-10).
 *
 * <h2>Why not a sequence</h2>
 *
 * {@code nextval()} is deliberately non-transactional — that is what makes it fast and
 * contention-free, and it is exactly wrong here. A sequence does not roll back, so a transaction
 * that fails after allocating a number burns it, and the invoice register acquires a hole. A hole
 * in an invoice register is indistinguishable from a deleted sale, and an auditor will treat it as
 * one. Purchase orders use a sequence precisely because nobody audits their numbering.
 *
 * <h2>What is used instead</h2>
 *
 * A single row in {@code document_counter}, read with {@code SELECT ... FOR UPDATE} and incremented
 * inside the caller's transaction. Three consequences, all wanted:
 *
 * <ul>
 *   <li>Two fulfilments issuing at once serialise on the row lock, so no two invoices share a
 *       number.
 *   <li>A crash between allocating and committing rolls the counter back with the invoice, so the
 *       number is reused by the next attempt rather than lost.
 *   <li>{@code invoice.sales_order_id} is unique, so a retried fulfilment after a partial failure
 *       is refused by the database before it can consume a second number.
 * </ul>
 *
 * The cost is that invoicing is serial. At this system's volumes that is not a cost.
 */
@Service
public class InvoiceService {

    private static final String COUNTER = "invoice";

    private final InvoiceRepository invoices;
    private final JdbcTemplate jdbc;

    public InvoiceService(InvoiceRepository invoices, JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.jdbc = jdbc;
    }

    /**
     * Issues the invoice for a fulfilled order.
     *
     * <p>{@code MANDATORY}: this must run inside the fulfilment transaction, never its own. On its
     * own it would commit a number for goods whose stock movement then failed to commit — which is
     * the gap this class exists to prevent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Invoice issueFor(SalesOrder order, UUID actorId) {
        long sequenceNo = takeNextNumber();

        Invoice invoice = new Invoice();
        invoice.setSequenceNo(sequenceNo);
        invoice.setInvoiceNumber(String.format("INV-%06d", sequenceNo));
        invoice.setSalesOrderId(order.getId());
        invoice.setCustomerId(order.getCustomerId());
        invoice.setTotal(order.getTotal());
        invoice.setIssuedBy(actorId);

        try {
            return invoices.saveAndFlush(invoice);
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.ifConstraintIs(
                    e,
                    "invoice_sales_order_id_key",
                    "INVOICE_ALREADY_ISSUED",
                    "That order already has an invoice.");
        }
    }

    /**
     * Takes the next number under a row lock.
     *
     * <p>Read and increment are two statements rather than one {@code UPDATE ... RETURNING}
     * because the {@code FOR UPDATE} makes the ordering explicit to anyone reading it. Either form
     * is correct; this one is harder to misread as a check-then-act.
     */
    private long takeNextNumber() {
        Long next =
                jdbc.queryForObject(
                        "SELECT next_value FROM document_counter WHERE name = ? FOR UPDATE",
                        Long.class,
                        COUNTER);
        if (next == null) {
            throw new IllegalStateException("document_counter row for '" + COUNTER + "' is missing");
        }
        jdbc.update("UPDATE document_counter SET next_value = ? WHERE name = ?", next + 1, COUNTER);
        return next;
    }

    @Transactional(readOnly = true)
    public Optional<Invoice> findForOrder(UUID salesOrderId) {
        return invoices.findBySalesOrderId(salesOrderId);
    }

    @Transactional(readOnly = true)
    public Invoice get(UUID id) {
        return invoices
                .findById(id)
                .orElseThrow(
                        () -> new NotFoundException("INVOICE_NOT_FOUND", "No invoice with that id."));
    }

    /** In number order, which is the order an auditor reads them in. */
    @Transactional(readOnly = true)
    public List<Invoice> list() {
        return invoices.findAllInSequence();
    }
}
