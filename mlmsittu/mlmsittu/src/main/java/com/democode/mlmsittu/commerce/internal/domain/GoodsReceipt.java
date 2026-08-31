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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One delivery put into stores. Usually against a purchase order; a PO may have many.
 *
 * <p>The receipt is the document; the stock movements it caused reference it by
 * {@code reference_type = 'goods_receipt'} and {@code reference_id}, so the ledger can always
 * answer "why did this stock appear".
 */
@Entity
@Table(name = "goods_receipt")
@Getter
@Setter
public class GoodsReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "receipt_number", nullable = false, unique = true, length = 32)
    private String receiptNumber;

    /** Null for a manual entry — goods that arrived without an order behind them. */
    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    /** {@code purchase_order} or {@code manual}; see {@code chk_receipt_source}. */
    @Column(name = "source", nullable = false, length = 16)
    private String source = "purchase_order";

    /** The store most of the delivery went to. Each line may override it. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "supplier_note", length = 500)
    private String supplierNote;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "goods_receipt_id", nullable = false)
    private List<GoodsReceiptLine> lines = new ArrayList<>();
}
