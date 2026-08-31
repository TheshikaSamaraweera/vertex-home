package com.democode.mlmsittu.inventory.internal.stock;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** The replay query. This is what {@code stock_level} is checked against, and rebuilt from. */
    @Query(
            """
            select new com.democode.mlmsittu.inventory.internal.stock.LedgerTotal(
                       m.itemId, m.locationId, coalesce(sum(m.qtyDelta), 0))
            from StockMovement m
            group by m.itemId, m.locationId
            order by m.itemId, m.locationId
            """)
    List<LedgerTotal> totalsByItemAndLocation();

    @Query(
            """
            select coalesce(sum(m.qtyDelta), 0) from StockMovement m
            where m.itemId = :itemId and m.locationId = :locationId
            """)
    long totalFor(@Param("itemId") UUID itemId, @Param("locationId") UUID locationId);

    @Query(
            """
            select m from StockMovement m
            where (:itemId is null or m.itemId = :itemId)
              and (:locationId is null or m.locationId = :locationId)
            order by m.id desc
            """)
    List<StockMovement> search(
            @Param("itemId") UUID itemId, @Param("locationId") UUID locationId);

    /**
     * The same search, one page at a time, newest first (P7-03).
     *
     * <p>This is the list that grows without bound — every movement the warehouse has ever made —
     * so it is the one where returning everything eventually stops working. {@code id} is a
     * BIGSERIAL and therefore already a total order, so "before this id" needs no tie-break.
     */
    @Query(
            """
            select m from StockMovement m
            where (:itemId is null or m.itemId = :itemId)
              and (:locationId is null or m.locationId = :locationId)
              and (:beforeId is null or m.id < :beforeId)
            order by m.id desc
            """)
    List<StockMovement> searchPage(
            @Param("itemId") UUID itemId,
            @Param("locationId") UUID locationId,
            @Param("beforeId") Long beforeId,
            Pageable pageable);

    List<StockMovement> findByReferenceTypeAndReferenceIdOrderByIdAsc(
            String referenceType, UUID referenceId);
}
