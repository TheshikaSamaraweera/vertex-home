package com.democode.mlmsittu.catalogue.api;

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
}
