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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "purchase_order")
@Getter
@Setter
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "po_number", nullable = false, unique = true, length = 32)
    private String poNumber;

    /** Supplier and location live in other aggregates — referenced by id, never mapped. */
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "status", nullable = false, length = 24)
    private String status = PurchaseOrderStatus.DRAFT.dbValue();

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** The address the order document actually went to, captured at send time. */
    @Column(name = "sent_to_email", length = 320)
    private String sentToEmail;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "arrived_by")
    private UUID arrivedBy;

    /**
     * The name the confirming person typed. Checked against their account before it is stored, so
     * it is an attestation rather than free text — "I, named here, saw this delivery".
     */
    @Column(name = "arrival_attested_name", length = 255)
    private String arrivalAttestedName;

    /**
     * Lines are part of the order aggregate, so the association is real — this is within one
     * module and one transactional boundary, which is where JPA associations belong.
     */
    @OneToMany(
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    @OrderBy("lineNo asc")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public PurchaseOrderStatus statusValue() {
        return PurchaseOrderStatus.fromDbValue(status);
    }

    public void setStatusValue(PurchaseOrderStatus value) {
        this.status = value.dbValue();
    }

    public BigDecimal total() {
        return lines.stream()
                .map(PurchaseOrderLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** True once no line has anything outstanding. */
    public boolean fullyReceived() {
        return lines.stream().allMatch(PurchaseOrderLine::isClosed);
    }

    public boolean anythingReceived() {
        return lines.stream().anyMatch(line -> line.getQuantityReceived() > 0);
    }
}
