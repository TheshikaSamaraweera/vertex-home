package com.democode.mlmsittu.commerce.internal.repo;

import com.democode.mlmsittu.commerce.internal.domain.GoodsReceipt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    @Query("select g from GoodsReceipt g where g.purchaseOrderId = :poId order by g.receivedAt asc")
    List<GoodsReceipt> findByPurchaseOrder(@Param("poId") UUID purchaseOrderId);

    @Query("select g from GoodsReceipt g order by g.receivedAt desc, g.receiptNumber desc")
    List<GoodsReceipt> findAllNewestFirst();

    @Query(value = "select nextval('goods_receipt_number_seq')", nativeQuery = true)
    long nextNumber();
}
