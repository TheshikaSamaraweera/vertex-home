package com.democode.mlmsittu.commerce.internal.repo;

import com.democode.mlmsittu.commerce.internal.domain.Payment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @Query("select p from Payment p where p.salesOrderId = :orderId order by p.recordedAt desc")
    List<Payment> findForOrder(@Param("orderId") UUID orderId);

    @Query("select p from Payment p where p.status = :status order by p.recordedAt")
    List<Payment> findByStatus(@Param("status") String status);

    @Query("select p from Payment p order by p.recordedAt desc")
    List<Payment> findAllOrdered();

    /**
     * Deliberately absent: any lookup by {@code bankRef}.
     *
     * <p>P5-05 requires the duplicate-reference error not to reveal which order used the reference
     * first. A finder here would make writing that leak a one-line change, so the uniqueness check
     * is left entirely to {@code idx_payment_bank_ref} — the database says yes or no and nothing
     * else.
     */
}
