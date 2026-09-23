package com.democode.mlmsittu.catalogue.api;

import com.democode.mlmsittu.shared.api.Cursor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The catalogue's published surface. Inventory and commerce depend on this and nothing else. */
public interface ItemCatalogue {

    Optional<ItemRef> findById(UUID itemId);

    /**
     * Bulk lookup, keyed by id. Procurement validates every line of an order at once; doing that
     * one query per line is how an N+1 gets into a hot path unnoticed.
     */
    Map<UUID, ItemRef> findAllById(Collection<UUID> itemIds);

    /** Active items only — the set that may appear on a new order. */
    List<ItemRef> findAllActive();

    /** Every item, active or not. Reorder scanning and reconciliation need the full set. */
    List<ItemRef> findAll();

    /**
     * One page of every item, active or not, ordered by name then id — the stock screen pages
     * through the catalogue and attaches levels to each page.
     *
     * @param search matched against name and SKU; null or blank for no filter
     * @param categoryId null for every category
     * @param after null for the first page, otherwise the cursor of the last row already shown
     * @param limit how many rows to fetch — callers ask for one more than they show, to learn
     *     whether another page exists
     */
    List<ItemRef> page(String search, UUID categoryId, Cursor after, int limit);
}
