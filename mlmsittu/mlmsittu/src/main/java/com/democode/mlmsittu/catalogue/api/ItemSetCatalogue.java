package com.democode.mlmsittu.catalogue.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Set composition, published to inventory.
 *
 * <p>The split of responsibility is deliberate: the catalogue knows what a set <em>is</em>, and
 * inventory knows what can be <em>had</em>. Availability is a stock question, so it does not live
 * here even though it is asked about a set.
 */
public interface ItemSetCatalogue {

    Optional<ItemSetRef> findById(UUID setId);

    List<ItemSetRef> findAllActive();

    List<ItemSetRef> findAll();

    /**
     * Items appearing in more than one active set.
     *
     * <p>These are the components whose availability figures are optimistic: two sets can each
     * report stock for the same units, and only one of them can ship. Architecture §4.2 calls the
     * figure advisory for exactly this reason, and the UI flags it.
     */
    Set<UUID> contendedComponentIds();
}
