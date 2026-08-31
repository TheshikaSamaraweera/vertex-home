package com.democode.mlmsittu.catalogue.internal.repo;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    Optional<Item> findBySku(String sku);

    boolean existsBySku(String sku);

    @Query("select i from Item i where i.active = true order by i.name asc, i.id asc")
    List<Item> findAllActiveOrdered();

    @Query("select i from Item i order by i.name asc, i.id asc")
    List<Item> findAllOrdered();

    /**
     * The first page, ordered exactly as {@link #findAllOrdered()}.
     *
     * <p>The order must match the keyset predicate below character for character. A page ordered
     * one way and continued another skips rows, and does it silently.
     */
    @Query(
            value =
                    """
                    SELECT * FROM item
                     WHERE (:includeInactive = true OR is_active = true)
                     ORDER BY name, id
                    """,
            nativeQuery = true)
    List<Item> firstPage(@Param("includeInactive") boolean includeInactive, Pageable pageable);

    /**
     * Everything strictly after {@code (name, id)}.
     *
     * <p>The tuple comparison is what makes the boundary exact: rows with a larger name, plus rows
     * with the same name and a larger id. Comparing on the name alone would repeat every item that
     * shares a name with the last row of the previous page, or skip them.
     *
     * <p><b>Native, and the reason is measured.</b> JPQL cannot express a row-value comparison, so
     * the same condition has to be written as {@code name > ? OR (name = ? AND id > ?)}. Postgres
     * cannot turn that OR into a single index seek — with 53,000 items it walked the index from the
     * beginning and took <b>45 ms</b> to return eleven rows. The row-value form
     * {@code (name, id) > (?, ?)} seeks straight to the boundary and takes <b>1.4 ms</b>. Same
     * rows, same index, thirty times the speed, and the gap widens as the catalogue grows.
     *
     * <p>Paired with {@code idx_item_name_id}; the ORDER BY must keep matching that index or the
     * seek turns back into a scan and a sort.
     */
    @Query(
            value =
                    """
                    SELECT * FROM item
                     WHERE (:includeInactive = true OR is_active = true)
                       AND (name, id) > (:name, CAST(:id AS uuid))
                     ORDER BY name, id
                    """,
            nativeQuery = true)
    List<Item> pageAfter(
            @Param("includeInactive") boolean includeInactive,
            @Param("name") String name,
            @Param("id") UUID id,
            Pageable pageable);
}
