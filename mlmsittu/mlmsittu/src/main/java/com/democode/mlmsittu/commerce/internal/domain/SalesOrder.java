package com.democode.mlmsittu.commerce.internal.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A sale, from the moment stock is held for it to the moment the goods leave (P5-02). */
@Entity
@Table(name = "sales_order")
@Getter
@Setter
public class SalesOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_number", nullable = false, unique = true, length = 32)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "status", nullable = false, length = 24)
    private String status = SalesOrderStatus.AWAITING_PAYMENT.dbValue();

    /**
     * The stock this order is holding, owned by the inventory module. Null once a rejection has
     * released it and before a fresh slip re-reserves.
     */
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "subtotal", nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount", nullable = false)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "total", nullable = false)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "idempotency_key", length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "sales_order_id", nullable = false)
    @OrderBy("lineNo asc")
    private List<SalesOrderLine> lines = new ArrayList<>();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public SalesOrderStatus statusValue() {
        return SalesOrderStatus.fromDbValue(status);
    }

    public void setStatusValue(SalesOrderStatus value) {
        this.status = value.dbValue();
    }

    /** Sums the lines and applies the discount. The discount can never make the total negative. */
    public void recalculateTotals() {
        this.subtotal =
                lines.stream()
                        .map(SalesOrderLine::getLineTotal)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.total = subtotal.subtract(discount).max(BigDecimal.ZERO);
    }
}
