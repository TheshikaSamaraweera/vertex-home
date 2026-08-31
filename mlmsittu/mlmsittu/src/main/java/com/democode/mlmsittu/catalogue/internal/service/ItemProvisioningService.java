package com.democode.mlmsittu.catalogue.internal.service;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockPosting;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates an item and gives it somewhere to live, in one transaction.
 *
 * <h2>Why this is a separate bean from {@code ItemService}</h2>
 *
 * {@code StockLedgerService} depends on {@code ItemCatalogue} — which {@code ItemService}
 * implements — so that a posting for an unknown item fails as a clean 404 rather than a foreign key
 * violation. Putting the stock call inside {@code ItemService} therefore closed a loop: the ledger
 * needs the catalogue to validate, and the catalogue would need the ledger to provision. Spring
 * refuses to construct that, and it is right to.
 *
 * <p>Splitting the coordination out breaks the cycle without weakening either side. The catalogue
 * still knows nothing about stock; the ledger still validates items; and this class, which is
 * allowed to know about both, sequences them. It is also the honest description of what is
 * happening — creating a product and stocking it are two acts, and this is the one place that says
 * they happen together.
 *
 * <h2>Why a location is always established</h2>
 *
 * An item with no {@code stock_level} row cannot be reserved at all — reservation answers
 * {@code STOCK_ROW_MISSING}, which reads like a fault rather than "nobody has put any of this
 * anywhere yet". So a position is created even at quantity zero, and a new item is sellable the
 * moment it exists.
 *
 * <p>A non-zero opening balance is <b>posted as a movement</b> rather than written onto the level.
 * Stock that appears without a movement behind it is stock that reconciliation would later erase,
 * because {@code stock_level} is a projection of the ledger and the ledger has to account for every
 * unit.
 */
@Service
public class ItemProvisioningService {

    private final ItemService items;
    private final StockLedger stock;
    private final LocationDirectory locations;

    public ItemProvisioningService(
            ItemService items, StockLedger stock, LocationDirectory locations) {
        this.items = items;
        this.stock = stock;
        this.locations = locations;
    }

    /**
     * Where a newly created item's stock starts.
     *
     * @param locationId null falls back to the default location
     * @param quantity zero still establishes the position
     */
    public record OpeningStock(UUID locationId, int quantity) {}

    /**
     * One transaction: if the opening stock cannot be posted, the item is not created either.
     * Half a creation — a product nobody can sell, with no indication why — is worse than a clean
     * failure the operator can correct and retry.
     */
    @Transactional
    public Item createWithOpeningStock(
            String sku, ItemService.ItemDetails details, OpeningStock opening, UUID actorId) {

        UUID locationId = resolveLocation(opening);
        Item saved = items.create(sku, details);

        if (opening != null && opening.quantity() > 0) {
            stock.post(
                    StockPosting.openingBalance(
                            saved.getId(), locationId, opening.quantity(), actorId));
        } else {
            // A movement of zero carries no information and the ledger refuses it, so an empty
            // position is established directly instead.
            stock.ensurePosition(saved.getId(), locationId);
        }

        return saved;
    }

    /** Resolved before the item is created, so a bad location fails before anything is written. */
    private UUID resolveLocation(OpeningStock opening) {
        if (opening == null || opening.locationId() == null) {
            return locations.defaultLocation().id();
        }
        return locations
                .findById(opening.locationId())
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "LOCATION_NOT_FOUND", "No location with that id."))
                .id();
    }
}
