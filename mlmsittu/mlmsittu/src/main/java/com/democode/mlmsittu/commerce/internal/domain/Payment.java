package com.democode.mlmsittu.commerce.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A bank transfer claimed against an order, and the human decision about it (architecture §5).
 *
 * <p>No gateway and no wallet: somebody transfers money, photographs the slip, and a finance
 * officer decides whether to believe it. {@code verifiedBy} records who made that decision either
 * way — a rejection is as much a decision as an approval, and the database refuses to store one
 * without a name against it.
 */
@Entity
@Table(name = "payment")
@Getter
@Setter
public class Payment {

    public static final String PENDING = "pending";
    public static final String VERIFIED = "verified";
    public static final String REJECTED = "rejected";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sales_order_id", nullable = false, updatable = false)
    private UUID salesOrderId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    /**
     * The bank's transaction reference, typed off the slip image by whoever recorded it.
     *
     * <p>Unique across every payment ever recorded — see {@code idx_payment_bank_ref}. That index
     * is the single most valuable control in this table.
     */
    @Column(name = "bank_ref", length = 128)
    private String bankRef;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "slip_document_id", nullable = false, updatable = false)
    private UUID slipDocumentId;

    @Column(name = "status", nullable = false, length = 24)
    private String status = PENDING;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt = Instant.now();

    /** Who decided. Never equal to {@link #recordedBy} — {@code chk_payment_four_eyes}. */
    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    public boolean isPending() {
        return PENDING.equals(status);
    }

    public void verify(UUID officerId) {
        this.status = VERIFIED;
        this.verifiedBy = officerId;
        this.verifiedAt = Instant.now();
    }

    public void reject(UUID officerId, String reason) {
        this.status = REJECTED;
        this.verifiedBy = officerId;
        this.verifiedAt = Instant.now();
        this.rejectionReason = reason;
    }
}
