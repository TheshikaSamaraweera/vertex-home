package com.democode.mlmsittu.catalogue.internal.repo;

import com.democode.mlmsittu.catalogue.internal.domain.ItemSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemSetRepository extends JpaRepository<ItemSet, UUID> {

    Optional<ItemSet> findByCode(String code);

    boolean existsByCode(String code);

    @Query("select s from ItemSet s where s.active = true order by s.name asc, s.id asc")
    List<ItemSet> findAllActiveOrdered();

    @Query("select s from ItemSet s order by s.name asc, s.id asc")
    List<ItemSet> findAllOrdered();

    /**
     * Component items that appear in more than one <em>active</em> set.
     *
     * <p>Deactivated sets are excluded on purpose: a set nobody can order does not compete for
     * stock, and counting it would flag contention that cannot happen.
     */
    @Query(
            """
            select line.itemId
            from ItemSet s join s.lines line
            where s.active = true
            group by line.itemId
            having count(distinct s.id) > 1
            """)
    List<UUID> findContendedComponentIds();

    /**
     * How many sold order lines name this set.
     *
     * <p>A native query on purpose: {@code sales_order_line} belongs to commerce and the catalogue
     * has no entity for it. Asking the database directly is honest about that — the alternative is
     * either a cross-module entity reference the boundary rules forbid, or a published API on
     * commerce whose only caller would be this one check.
     */
    @Query(
            value = "SELECT count(*) FROM sales_order_line WHERE set_id = :setId",
            nativeQuery = true)
    long countSalesLinesFor(@Param("setId") UUID setId);
}
