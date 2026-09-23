package com.democode.mlmsittu.commerce.internal.repo;

import com.democode.mlmsittu.commerce.internal.domain.SalesOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID> {

    /**
     * Locks the order for the duration of a status change.
     *
     * <p>Payment verification, rejection, fulfilment and cancellation all read the status, decide,
     * and write. Without the lock, a verify and a cancel arriving together would both read
     * {@code payment_review}, both consider themselves valid, and one of them would release stock
     * the other had just committed to shipping.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id")
    Optional<SalesOrder> lockForUpdate(@Param("id") UUID id);

    /** P5-03. Scoped to the sender, so one operator's keys are invisible to another's. */
    Optional<SalesOrder> findByCreatedByAndIdempotencyKey(UUID createdBy, String idempotencyKey);

    @Query("select o from SalesOrder o order by o.createdAt desc")
    List<SalesOrder> findAllOrdered();

    @Query("select o from SalesOrder o where o.customerId = :customerId order by o.createdAt desc")
    List<SalesOrder> findForCustomer(@Param("customerId") UUID customerId);

    @Query("select o from SalesOrder o where o.status = :status order by o.createdAt")
    List<SalesOrder> findByStatus(@Param("status") String status);

    /**
     * The newest orders, optionally of one status — the first page of the sales screen.
     *
     * <p>Ordered by {@code (created_at, id)} descending: two orders placed in the same microsecond
     * are still in a total order, so a page boundary between them is exact. {@code search} matches
     * the order number or the buyer's name. Both filters are the empty string for "any", because a
     * null string in a native query cannot be typed.
     */
    @Query(
            value =
                    """
                    SELECT * FROM sales_order
                     WHERE (:status = '' OR status = :status)
                       AND (:search = ''
                            OR lower(order_number) LIKE '%' || lower(:search) || '%'
                            OR customer_id IN (
                                SELECT c.id FROM customer c
                                 WHERE lower(c.name) LIKE '%' || lower(:search) || '%'))
                     ORDER BY created_at DESC, id DESC
                    """,
            nativeQuery = true)
    List<SalesOrder> firstPage(
            @Param("status") String status, @Param("search") String search, Pageable pageable);

    /** Everything strictly older than {@code (createdAt, id)}, in the same order. */
    @Query(
            value =
                    """
                    SELECT * FROM sales_order
                     WHERE (:status = '' OR status = :status)
                       AND (:search = ''
                            OR lower(order_number) LIKE '%' || lower(:search) || '%'
                            OR customer_id IN (
                                SELECT c.id FROM customer c
                                 WHERE lower(c.name) LIKE '%' || lower(:search) || '%'))
                       AND (created_at, id) < (CAST(:createdAt AS timestamptz), CAST(:id AS uuid))
                     ORDER BY created_at DESC, id DESC
                    """,
            nativeQuery = true)
    List<SalesOrder> pageBefore(
            @Param("status") String status,
            @Param("search") String search,
            @Param("createdAt") String createdAt,
            @Param("id") UUID id,
            Pageable pageable);

    /**
     * Document numbering from a sequence. A rolled-back order leaves a gap in order numbers, which
     * nobody audits. <b>Invoices are different</b> — see {@code InvoiceService}.
     */
    @Query(value = "select nextval('sales_order_number_seq')", nativeQuery = true)
    long nextNumber();
}
