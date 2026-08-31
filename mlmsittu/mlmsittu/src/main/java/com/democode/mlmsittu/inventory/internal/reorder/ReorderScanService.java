package com.democode.mlmsittu.inventory.internal.reorder;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises an alert when stock falls below an item's reorder level, and clears it when stock
 * recovers (development plan P2-09).
 *
 * <p>Compares against <b>available</b> stock rather than on-hand. Reserved units are already
 * promised to someone; counting them as replenishment cover is how a warehouse ends up unable to
 * fulfil an order it has already confirmed.
 *
 * <p><b>Per item, not per item-and-store</b> (V18). The original rule raised an alert for every
 * (item, location) pair, which was defensible with one store and wrong with two: creating a second
 * store instantly raised an alert for every tracked item in it, each sitting at zero and none of
 * them actually short. Worse, splitting a delivery across two stores could leave both halves under
 * the threshold while the business held plenty. What the threshold is really asking is "do we need
 * to buy more of this", and that is a question about the total.
 *
 * <p>Where the stock physically sits is a separate question, and the per-store breakdown on the
 * stock screen is where it gets answered.
 */
@Service
public class ReorderScanService {

    private static final Logger log = LoggerFactory.getLogger(ReorderScanService.class);

    private final ItemCatalogue items;
    private final LocationDirectory locations;
    private final StockLedger stock;
    private final ReorderAlertRepository alerts;

    public ReorderScanService(
            ItemCatalogue items,
            LocationDirectory locations,
            StockLedger stock,
            ReorderAlertRepository alerts) {
        this.items = items;
        this.locations = locations;
        this.stock = stock;
        this.alerts = alerts;
    }

    public record ScanResult(int itemsScanned, int alertsRaised, int alertsCleared, int openTotal) {}

    /**
     * Scheduled hourly. Architecture §10.1 puts {@code @Scheduled} work on a dedicated instance via
     * a Spring profile so it does not multiply across replicas — that split arrives in Phase 8.
     * Until then this runs in the single local instance, which is correct by accident but correct.
     */
    @Scheduled(fixedDelayString = "${jobs.reorder-scan.interval-ms:3600000}", initialDelay = 60_000)
    public void scheduledScan() {
        ScanResult result = scan();
        if (result.alertsRaised() > 0 || result.alertsCleared() > 0) {
            log.info(
                    "Reorder scan: {} raised, {} cleared, {} open",
                    result.alertsRaised(),
                    result.alertsCleared(),
                    result.openTotal());
        }
    }

    @Transactional
    public ScanResult scan() {
        List<ItemRef> allItems = items.findAll();

        // Only active stores count towards cover. Stock in a store nobody picks from is not
        // available to fulfil anything, so treating it as replenishment would be a lie.
        Set<UUID> countable = new HashSet<>();
        for (LocationRef location : locations.findAllActive()) {
            countable.add(location.id());
        }

        Map<UUID, Integer> availableByItem = new HashMap<>();
        for (StockView view : stock.allLevels()) {
            if (countable.contains(view.locationId())) {
                availableByItem.merge(view.itemId(), view.available(), Integer::sum);
            }
        }

        int raised = 0;
        int cleared = 0;

        for (ItemRef item : allItems) {
            // A reorder level of zero means "do not track" — the overwhelmingly common case for
            // consumables nobody replenishes on a threshold.
            if (item.reorderLevel() <= 0) {
                continue;
            }

            int available = availableByItem.getOrDefault(item.id(), 0);
            Optional<ReorderAlert> open = alerts.findOpenFor(item.id());

            if (available <= item.reorderLevel()) {
                if (open.isEmpty()) {
                    ReorderAlert alert = new ReorderAlert();
                    alert.setItemId(item.id());
                    // Deliberately no location: the shortage is the item's, not one store's.
                    alert.setReorderLevel(item.reorderLevel());
                    alert.setOnHandAtDetection(available);
                    alerts.save(alert);
                    raised++;
                }
                // Already open: leave it alone. Re-raising on every scan would bury the
                // procurement team in duplicates of a fact they already know.
            } else if (open.isPresent()) {
                ReorderAlert alert = open.get();
                alert.clear(available);
                alerts.save(alert);
                cleared++;
            }
        }

        return new ScanResult(allItems.size(), raised, cleared, alerts.findAllOpen().size());
    }

    @Transactional(readOnly = true)
    public List<ReorderAlert> listAlerts(boolean openOnly) {
        return openOnly ? alerts.findAllOpen() : alerts.findAllOrdered();
    }

}
