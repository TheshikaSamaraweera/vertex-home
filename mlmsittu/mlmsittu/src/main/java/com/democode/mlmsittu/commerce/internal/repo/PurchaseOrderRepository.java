package com.democode.mlmsittu.commerce.internal.repo;

import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    /**
     * Locks the order for the duration of a receipt.
     *
     * <p>Two deliveries booked against the same order at the same moment would otherwise both read
     * the same {@code quantity_received} and the second would overwrite the first's update — a
     * lost update that quietly loses stock. The lock makes receipts against one order serial,
     * which they physically are anyway.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    Optional<PurchaseOrder> lockForUpdate(@Param("id") UUID id);

    @Query("select p from PurchaseOrder p order by p.createdAt desc")
    List<PurchaseOrder> findAllOrdered();

    @Query("select p from PurchaseOrder p where p.supplierId = :supplierId order by p.createdAt desc")
    List<PurchaseOrder> findBySupplier(@Param("supplierId") UUID supplierId);

    /** Used to decide whether a supplier can be deleted outright. */
    long countBySupplierId(UUID supplierId);

    /**
     * Document numbering from a sequence. A rolled-back transaction leaves a gap, which is
     * acceptable for a purchase order. <b>Do not copy this for invoices</b> — P5-10 requires those
     * to be gap-free and needs a different mechanism.
     */
    @Query(value = "select nextval('purchase_order_number_seq')", nativeQuery = true)
    long nextNumber();
}
