package com.democode.mlmsittu.commerce.internal.service;

import com.democode.mlmsittu.commerce.internal.domain.Payment;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrder;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrderStatus;
import com.democode.mlmsittu.commerce.internal.repo.PaymentRepository;
import com.democode.mlmsittu.inventory.api.ReservationView;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.ForbiddenException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import com.democode.mlmsittu.shared.storage.api.DocumentVault;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bank transfer slips and the human decision about them (P5-04 to P5-07, architecture §5).
 *
 * <h2>The two controls that matter</h2>
 *
 * <ol>
 *   <li><b>One slip, one order.</b> Photographing a genuine transfer and submitting it against
 *       several orders is the dominant fraud in slip-based systems. A unique index on the bank
 *       reference stops it, and the refusal deliberately does not say which order got there first.
 *   <li><b>Whoever recorded it cannot bless it.</b> Enforced here, and again by
 *       {@code chk_payment_four_eyes} in the database, because a control that lives in one place
 *       lives in no place.
 * </ol>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final SalesOrderService orders;
    private final DocumentVault vault;

    public PaymentService(
            PaymentRepository payments, SalesOrderService orders, DocumentVault vault) {
        this.payments = payments;
        this.orders = orders;
        this.vault = vault;
    }

    public record PaymentEntry(
            UUID salesOrderId,
            BigDecimal amount,
            String bankRef,
            LocalDate paidOn,
            UUID slipDocumentId) {}

    // ==============================================================================
    // Recording (P5-04, P5-05)
    // ==============================================================================

    @Transactional
    @Audited(action = "PAYMENT_RECORDED", entityType = "payment", auditFailures = true)
    public Payment record(PaymentEntry entry, UUID actorId) {
        SalesOrder order = orders.lock(entry.salesOrderId());
        assertAcceptsPayment(order);

        vault.find(entry.slipDocumentId())
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "DOCUMENT_NOT_FOUND",
                                        "That slip upload could not be found. Upload it again."));

        if (entry.amount() == null || entry.amount().signum() <= 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "The amount must be positive.");
        }

        // A rejected order gave its stock back, so a fresh attempt has to take it again — and may
        // legitimately fail if somebody else bought it in the meantime. Better an honest refusal
        // now than a fulfilment that cannot be met later.
        if (order.getReservationId() == null) {
            ReservationView reservation = orders.reserveFor(order, actorId);
            order.setReservationId(reservation.id());
            log.info(
                    "Order {} re-reserved stock for a resubmitted payment", order.getOrderNumber());
        }

        Payment payment = new Payment();
        payment.setSalesOrderId(order.getId());
        payment.setAmount(entry.amount());
        payment.setBankRef(blankToNull(entry.bankRef()));
        payment.setPaidOn(entry.paidOn());
        payment.setSlipDocumentId(entry.slipDocumentId());
        payment.setRecordedBy(actorId);
        payment.setRecordedAt(Instant.now());

        Payment saved = persist(payment);

        order.setStatusValue(SalesOrderStatus.PAYMENT_REVIEW);
        orders.save(order);

        AuditContext.record(saved.getId(), null, snapshot(saved));
        return saved;
    }

    /**
     * P5-05 · the duplicate-reference refusal.
     *
     * <p>The message says the reference is already recorded and stops there. Naming the order that
     * used it would tell anyone with a valid slip which other orders exist and what they are
     * numbered — an enumeration oracle handed out in an error message. The verifier who needs to
     * investigate has the reference and can search for it with the access their role already
     * grants.
     */
    private Payment persist(Payment payment) {
        try {
            return payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.ifConstraintIs(
                    e,
                    "idx_payment_bank_ref",
                    "DUPLICATE_BANK_REFERENCE",
                    "That bank reference has already been recorded against a payment.");
        }
    }

    private void assertAcceptsPayment(SalesOrder order) {
        SalesOrderStatus status = order.statusValue();
        boolean acceptable =
                status == SalesOrderStatus.AWAITING_PAYMENT
                        || status == SalesOrderStatus.PAYMENT_REJECTED;

        if (!acceptable) {
            ConflictException conflict =
                    new ConflictException(
                            "ORDER_NOT_AWAITING_PAYMENT",
                            "That order is not waiting for a payment.");
            conflict.with("orderStatus", order.getStatus());
            throw conflict;
        }
    }

    // ==============================================================================
    // Verification (P5-06, P5-07)
    // ==============================================================================

    @Transactional
    @Audited(action = "PAYMENT_VERIFIED", entityType = "payment", auditFailures = true)
    public Payment verify(UUID paymentId, UUID officerId) {
        Payment payment = requirePending(paymentId);
        assertNotOwnEntry(payment, officerId);
        Map<String, Object> before = snapshot(payment);

        SalesOrder order = orders.lock(payment.getSalesOrderId());
        assertUnderReview(order);

        payment.verify(officerId);
        Payment saved = payments.save(payment);

        order.setStatusValue(SalesOrderStatus.PAID);
        order.setPaidAt(Instant.now());
        orders.save(order);

        AuditContext.record(paymentId, before, snapshot(saved));
        log.info("Payment {} verified by {} — order {} is paid", paymentId, officerId, order.getOrderNumber());
        return saved;
    }

    /**
     * P5-09 · rejection releases the reservation.
     *
     * <p>The stock goes straight back into availability rather than sitting behind a sale that is
     * not going to happen. The order is not cancelled: the buyer can submit a corrected slip, and
     * {@link #record} re-reserves at that point.
     */
    @Transactional
    @Audited(action = "PAYMENT_REJECTED", entityType = "payment", auditFailures = true)
    public Payment reject(UUID paymentId, UUID officerId, String reason) {
        Payment payment = requirePending(paymentId);
        assertNotOwnEntry(payment, officerId);
        Map<String, Object> before = snapshot(payment);

        SalesOrder order = orders.lock(payment.getSalesOrderId());
        assertUnderReview(order);

        payment.reject(officerId, reason);
        Payment saved = payments.save(payment);

        orders.releaseHeldStock(order, "payment rejected");
        order.setStatusValue(SalesOrderStatus.PAYMENT_REJECTED);
        orders.save(order);

        AuditContext.record(paymentId, before, snapshot(saved));
        log.info("Payment {} rejected by {} — stock released", paymentId, officerId);
        return saved;
    }

    /**
     * Separation of duties (§8.1, P5-07).
     *
     * <p>403 rather than 409: the request is well formed and the payment is in a state that could
     * be verified — it is <em>this person</em> who may not do it.
     */
    private void assertNotOwnEntry(Payment payment, UUID officerId) {
        if (payment.getRecordedBy().equals(officerId)) {
            throw new ForbiddenException(
                    "SELF_VERIFICATION_FORBIDDEN",
                    "A payment must be checked by someone other than the person who recorded it.");
        }
    }

    private void assertUnderReview(SalesOrder order) {
        if (order.statusValue() != SalesOrderStatus.PAYMENT_REVIEW) {
            ConflictException conflict =
                    new ConflictException(
                            "ORDER_NOT_UNDER_REVIEW",
                            "That order is no longer waiting on a payment decision.");
            conflict.with("orderStatus", order.getStatus());
            throw conflict;
        }
    }

    private Payment requirePending(UUID paymentId) {
        Payment payment = require(paymentId);
        if (!payment.isPending()) {
            ConflictException conflict =
                    new ConflictException(
                            "PAYMENT_ALREADY_DECIDED", "That payment has already been decided.");
            conflict.with("paymentStatus", payment.getStatus());
            throw conflict;
        }
        return payment;
    }

    // ==============================================================================
    // Reads
    // ==============================================================================

    /**
     * Authorises a look at the slip image and returns a one-shot token.
     *
     * <p>Slips go through the same vault as KYC scans, so opening one is recorded in
     * {@code document_access_log} exactly as opening a NIC is. A finance officer looking at
     * somebody's bank details is an access worth being able to account for.
     */
    @Transactional
    public String issueSlipAccess(UUID paymentId, UUID officerId, String ip) {
        Payment payment = require(paymentId);
        return vault.issueAccessToken(payment.getSlipDocumentId(), officerId, null, ip);
    }

    public long slipTokenTtlSeconds() {
        return vault.tokenTtlSeconds();
    }

    @Transactional(readOnly = true)
    public List<Payment> list(String status) {
        if (status == null || status.isBlank()) {
            return payments.findAllOrdered();
        }
        return payments.findByStatus(status);
    }

    @Transactional(readOnly = true)
    public List<Payment> forOrder(UUID orderId) {
        return payments.findForOrder(orderId);
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return require(id);
    }

    private Payment require(UUID id) {
        return payments
                .findById(id)
                .orElseThrow(
                        () -> new NotFoundException("PAYMENT_NOT_FOUND", "No payment with that id."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> snapshot(Payment payment) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("salesOrderId", payment.getSalesOrderId());
        snapshot.put("amount", payment.getAmount());
        snapshot.put("bankRef", payment.getBankRef());
        snapshot.put("paymentStatus", payment.getStatus());
        snapshot.put("verifiedBy", payment.getVerifiedBy());
        return snapshot;
    }
}
