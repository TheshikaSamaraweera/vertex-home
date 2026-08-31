package com.democode.mlmsittu.inventory.internal.service;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.catalogue.api.ItemSetCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemSetRef;
import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.inventory.api.SetAvailability;
import com.democode.mlmsittu.inventory.api.SetAvailability.ComponentAvailability;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockView;
import com.democode.mlmsittu.inventory.internal.location.LocationService;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Item set availability (development plan P3-02, P3-03).
 *
 * <p><b>Computed on every read, never stored.</b> A stored figure would be wrong the moment any
 * component moved, and there is no cheap way to know which sets a movement affects without doing
 * this calculation anyway.
 */
@Service
public class AvailabilityService {

    private final ItemSetCatalogue setCatalogue;
    private final ItemCatalogue itemCatalogue;
    private final StockLedger ledger;
    private final LocationService locations;

    public AvailabilityService(
            ItemSetCatalogue setCatalogue,
            ItemCatalogue itemCatalogue,
            StockLedger ledger,
            LocationService locations) {
        this.setCatalogue = setCatalogue;
        this.itemCatalogue = itemCatalogue;
        this.ledger = ledger;
        this.locations = locations;
    }

    @Transactional(readOnly = true)
    public SetAvailability availabilityOf(UUID setId, UUID locationId) {
        ItemSetRef set =
                setCatalogue
                        .findById(setId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "ITEM_SET_NOT_FOUND", "No item set with that id."));

        LocationRef location = locations.require(locationId);
        return compute(set, location.id(), stockAt(location.id()), setCatalogue.contendedComponentIds());
    }

    @Transactional(readOnly = true)
    public List<SetAvailability> availabilityOfAll(UUID locationId, boolean includeInactive) {
        LocationRef location = locations.require(locationId);
        Map<UUID, StockView> stock = stockAt(location.id());
        Set<UUID> contended = setCatalogue.contendedComponentIds();

        List<ItemSetRef> sets =
                includeInactive ? setCatalogue.findAll() : setCatalogue.findAllActive();

        return sets.stream().map(set -> compute(set, location.id(), stock, contended)).toList();
    }

    // ------------------------------------------------------------------ the calculation

    private SetAvailability compute(
            ItemSetRef set,
            UUID locationId,
            Map<UUID, StockView> stock,
            Set<UUID> contendedComponents) {

        Map<UUID, ItemRef> items = itemCatalogue.findAllById(set.components().keySet());

        List<ComponentAvailability> components = new ArrayList<>();
        int availableSets = Integer.MAX_VALUE;
        UUID limitingItemId = null;
        boolean contended = false;

        for (Map.Entry<UUID, Integer> component : set.components().entrySet()) {
            UUID itemId = component.getKey();
            int perSet = component.getValue();

            StockView view = stock.get(itemId);
            int onHand = view == null ? 0 : view.onHand();
            int reserved = view == null ? 0 : view.reserved();

            // available, not on hand: units already reserved are promised to someone else, and
            // counting them here is precisely how a set gets sold twice.
            int available = onHand - reserved;

            int setsSupported = available / perSet;
            boolean componentContended = contendedComponents.contains(itemId);
            contended |= componentContended;

            ItemRef item = items.get(itemId);
            components.add(
                    new ComponentAvailability(
                            itemId,
                            item == null ? null : item.sku(),
                            item == null ? null : item.name(),
                            perSet,
                            onHand,
                            reserved,
                            available,
                            setsSupported,
                            componentContended));

            // The scarcest component decides. Strictly less-than keeps the first of several
            // equally-scarce components, which makes the answer stable across calls.
            if (setsSupported < availableSets) {
                availableSets = setsSupported;
                limitingItemId = itemId;
            }
        }

        if (components.isEmpty()) {
            availableSets = 0;
        }

        return new SetAvailability(
                set.id(),
                set.code(),
                set.name(),
                set.setPrice(),
                locationId,
                Math.max(availableSets, 0),
                contended,
                limitingItemId,
                components);
    }

    private Map<UUID, StockView> stockAt(UUID locationId) {
        Map<UUID, StockView> byItem = new HashMap<>();
        ledger.levelsAt(locationId).forEach(view -> byItem.put(view.itemId(), view));
        return byItem;
    }
}
