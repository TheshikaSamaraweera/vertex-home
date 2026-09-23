package com.democode.mlmsittu.inventory.internal.reorder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReorderAlertRepository extends JpaRepository<ReorderAlert, UUID> {

    @Modifying
    @Query(value = "DELETE FROM reorder_alert WHERE item_id = :itemId", nativeQuery = true)
    int deleteForItem(@Param("itemId") UUID itemId);

    @Query("select a from ReorderAlert a where a.itemId = :itemId and a.status = 'open'")
    Optional<ReorderAlert> findOpenFor(@Param("itemId") UUID itemId);

    @Query("select a from ReorderAlert a where a.status = 'open' order by a.raisedAt desc")
    List<ReorderAlert> findAllOpen();

    @Query("select a from ReorderAlert a order by a.raisedAt desc")
    List<ReorderAlert> findAllOrdered();
}
