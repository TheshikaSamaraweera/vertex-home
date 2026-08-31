package com.democode.mlmsittu.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * How many of a set could be assembled from stock on hand right now.
 *
 * <p><b>Advisory, not authoritative.</b> When two sets share a component they can each report the
 * same units as available, and only one of them can actually ship. {@code contended} says so
 * explicitly. The only figure that settles the question is whether a reservation succeeds
 * (architecture §4.2).
 *
 * @param limitingItemId the scarcest component — the one that decided {@code availableSets}
 */
public record SetAvailability(
        UUID setId,
        String code,
        String name,
        /** The set's own price, so a screen showing sets need not fetch each one to display it. */
        java.math.BigDecimal setPrice,
        UUID locationId,
        int availableSets,
        boolean contended,
        UUID limitingItemId,
        List<ComponentAvailability> components) {

    /**
     * @param name the item's name. A SKU alone tells whoever is reading the set nothing about what
     *     is actually short — {@code SLV-001} means something only to the person who wrote it.
     * @param perSet how many of this item one set contains
     * @param setsSupported {@code floor(available / perSet)} — how many sets this component alone
     *     could cover. Still computed because {@code availableSets} is derived from it and the
     *     limiting component is found with it; the screens no longer show it per row.
     */
    public record ComponentAvailability(
            UUID itemId,
            String sku,
            String name,
            int perSet,
            int onHand,
            int reserved,
            int available,
            int setsSupported,
            boolean contended) {}
}
