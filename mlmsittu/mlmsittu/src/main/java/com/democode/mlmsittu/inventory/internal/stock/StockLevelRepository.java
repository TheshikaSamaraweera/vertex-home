package com.democode.mlmsittu.inventory.internal.stock;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StockLevelRepository extends JpaRepository<StockLevel, StockLevelId> {

    /**
     * {@code SELECT … FOR UPDATE}. Every write path takes this before reading a balance it intends
     * to act on — a read followed by a write without the lock is a check that isn't a check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockLevel s where s.itemId = :itemId and s.locationId = :locationId")
    Optional<StockLevel> lockForUpdate(
            @Param("itemId") UUID itemId, @Param("locationId") UUID locationId);

    /**
     * Create the row if it is not there yet, without failing when a concurrent transaction is
     * creating the same one.
     *
     * <p>The obvious alternative — check, then insert — races: two postings for an item that has
     * never moved would both see nothing and both insert, and one would die on the primary key.
     * {@code ON CONFLICT DO NOTHING} pushes that decision into the database, which is the only
     * place that can make it correctly.
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO stock_level (item_id, location_id, on_hand, reserved, version)
                    VALUES (:itemId, :locationId, 0, 0, 0)
                    ON CONFLICT (item_id, location_id) DO NOTHING
                    """,
            nativeQuery = true)
    void ensureRowExists(@Param("itemId") UUID itemId, @Param("locationId") UUID locationId);

    @Query("select s from StockLevel s where s.locationId = :locationId order by s.itemId")
    List<StockLevel> findAtLocation(@Param("locationId") UUID locationId);

    @Query("select s from StockLevel s order by s.itemId, s.locationId")
    List<StockLevel> findAllOrdered();
}
