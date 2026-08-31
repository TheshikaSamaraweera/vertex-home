package com.democode.mlmsittu.commerce.internal.repo;

import com.democode.mlmsittu.commerce.internal.domain.ItemSupplierPrice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemSupplierPriceRepository extends JpaRepository<ItemSupplierPrice, UUID> {

    @Query("select p from ItemSupplierPrice p where p.itemId = :itemId order by p.price")
    List<ItemSupplierPrice> findForItem(@Param("itemId") UUID itemId);

    Optional<ItemSupplierPrice> findByItemIdAndSupplierId(UUID itemId, UUID supplierId);

    long countBySupplierId(UUID supplierId);

    /**
     * Every price a supplier quotes, keyed for a whole purchase order at once.
     *
     * <p>A ten-line order would otherwise be ten queries — the N+1 that only shows up once somebody
     * builds a realistic order.
     */
    @Query("select p from ItemSupplierPrice p where p.supplierId = :supplierId and p.itemId in :itemIds")
    List<ItemSupplierPrice> findForSupplierAndItems(
            @Param("supplierId") UUID supplierId, @Param("itemIds") List<UUID> itemIds);
}
