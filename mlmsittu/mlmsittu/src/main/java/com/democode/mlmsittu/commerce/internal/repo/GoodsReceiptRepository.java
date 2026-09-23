package com.democode.mlmsittu.commerce.internal.repo;

import com.democode.mlmsittu.commerce.internal.domain.GoodsReceipt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    @Query("select g from GoodsReceipt g where g.purchaseOrderId = :poId order by g.receivedAt asc")
    List<GoodsReceipt> findByPurchaseOrder(@Param("poId") UUID purchaseOrderId);

    @Query("select g from GoodsReceipt g order by g.receivedAt desc, g.receiptNumber desc")
    List<GoodsReceipt> findAllNewestFirst();

    /**
     * The newest receipts, by {@code (received_at, id)} so the order is total.
     *
     * <p>Each filter is the empty string for "any"; see {@code PurchaseOrderRepository} for why the
     * uuid is cast through {@code NULLIF}.
     */
    @Query(
            value =
                    """
                    SELECT * FROM goods_receipt
                     WHERE (:source = '' OR source = :source)
                       AND (:supplier = '' OR supplier_id = CAST(NULLIF(:supplier, '') AS uuid))
                       AND (:search = '' OR lower(receipt_number) LIKE '%' || lower(:search) || '%')
                     ORDER BY received_at DESC, id DESC
                    """,
            nativeQuery = true)
    List<GoodsReceipt> firstPage(
            @Param("source") String source,
            @Param("supplier") String supplier,
            @Param("search") String search,
            Pageable pageable);

    @Query(
            value =
                    """
                    SELECT * FROM goods_receipt
                     WHERE (:source = '' OR source = :source)
                       AND (:supplier = '' OR supplier_id = CAST(NULLIF(:supplier, '') AS uuid))
                       AND (:search = '' OR lower(receipt_number) LIKE '%' || lower(:search) || '%')
                       AND (received_at, id) < (CAST(:receivedAt AS timestamptz), CAST(:id AS uuid))
                     ORDER BY received_at DESC, id DESC
                    """,
            nativeQuery = true)
    List<GoodsReceipt> pageBefore(
            @Param("source") String source,
            @Param("supplier") String supplier,
            @Param("search") String search,
            @Param("receivedAt") String receivedAt,
            @Param("id") UUID id,
            Pageable pageable);

    @Query(value = "select nextval('goods_receipt_number_seq')", nativeQuery = true)
    long nextNumber();
}
