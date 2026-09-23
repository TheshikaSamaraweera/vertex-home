package com.democode.mlmsittu.commerce.internal.repo;

import com.democode.mlmsittu.commerce.internal.domain.Invoice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findBySalesOrderId(UUID salesOrderId);

    @Query("select i from Invoice i order by i.sequenceNo")
    List<Invoice> findAllInSequence();

    /** The newest invoices. The sequence is unique and gap-free, so it alone is a total order. */
    @Query(
            """
            select i from Invoice i
             where (:search = '' or lower(i.invoiceNumber) like concat('%', lower(:search), '%'))
             order by i.sequenceNo desc
            """)
    List<Invoice> firstPage(@Param("search") String search, Pageable pageable);

    @Query(
            """
            select i from Invoice i
             where (:search = '' or lower(i.invoiceNumber) like concat('%', lower(:search), '%'))
               and i.sequenceNo < :before
             order by i.sequenceNo desc
            """)
    List<Invoice> pageBefore(
            @Param("search") String search, @Param("before") long before, Pageable pageable);

    @Query("select i from Invoice i where i.customerId = :customerId order by i.issuedAt desc")
    List<Invoice> findForCustomer(@Param("customerId") UUID customerId);
}
