package com.democode.mlmsittu.inventory.internal.web;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockMovementView;
import com.democode.mlmsittu.inventory.api.StockView;
import com.democode.mlmsittu.inventory.internal.location.LocationService;
import com.democode.mlmsittu.inventory.internal.reorder.ReorderScanService;
import com.democode.mlmsittu.inventory.internal.service.StockAdjustmentService;
import com.democode.mlmsittu.inventory.internal.stock.StockReconciliationService;
import com.democode.mlmsittu.inventory.internal.web.dto.InventoryDtos.AdjustStockRequest;
import com.democode.mlmsittu.inventory.internal.web.dto.InventoryDtos.LocationResponse;
import com.democode.mlmsittu.inventory.internal.web.dto.InventoryDtos.StoreRequest;
import com.democode.mlmsittu.inventory.internal.web.dto.InventoryDtos.ReorderAlertResponse;
import com.democode.mlmsittu.inventory.internal.web.dto.InventoryDtos.StockByItemResponse;
import com.democode.mlmsittu.inventory.internal.web.dto.InventoryDtos.StockInStoreResponse;
import com.democode.mlmsittu.inventory.internal.web.dto.InventoryDtos.StoreDetailResponse;
import com.democode.mlmsittu.inventory.internal.web.dto.InventoryDtos.StoreItemResponse;
import com.democode.mlmsittu.inventory.internal.web.dto.InventoryDtos.StockLevelResponse;
import com.democode.mlmsittu.shared.api.Cursor;
import com.democode.mlmsittu.shared.api.PagedResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('STAFF')")
public class InventoryController {

    /** History is read newest-first and rarely scrolled far; a big first page suits that. */
    private static final int MOVEMENT_PAGE = 200;

    private final StockLedger ledger;
    private final StockAdjustmentService adjustments;
    private final StockReconciliationService reconciliation;
    private final ReorderScanService reorderScan;
    private final LocationService locations;
    private final ItemCatalogue items;
    private final CurrentUser currentUser;

    public InventoryController(
            StockLedger ledger,
            StockAdjustmentService adjustments,
            StockReconciliationService reconciliation,
            ReorderScanService reorderScan,
            LocationService locations,
            ItemCatalogue items,
            CurrentUser currentUser) {
        this.ledger = ledger;
        this.adjustments = adjustments;
        this.reconciliation = reconciliation;
        this.reorderScan = reorderScan;
        this.locations = locations;
        this.items = items;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ locations

    @GetMapping("/locations")
    public PagedResponse<LocationResponse> listLocations() {
        return PagedResponse.of(locations.findAllStores().stream().map(this::storeOf).toList());
    }

    /**
     * Creating a store.
     *
     * <p>The inventory clerk owns this. Deciding that a second warehouse exists is a stock
     * decision, not a purchasing one, and every screen that assigns goods to a store is theirs.
     */
    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public LocationResponse createStore(@Valid @RequestBody StoreRequest body) {
        return storeOf(
                locations.create(
                        body.code(),
                        new LocationService.StoreDetails(body.name(), body.address())));
    }

    /** The code is fixed once created — stock rows and receipts point at the store. */
    @PutMapping("/locations/{id}")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public LocationResponse updateStore(
            @PathVariable UUID id, @Valid @RequestBody StoreRequest body) {
        return storeOf(
                locations.update(
                        id, new LocationService.StoreDetails(body.name(), body.address())));
    }

    @PostMapping("/locations/{id}/deactivate")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public LocationResponse deactivateStore(@PathVariable UUID id) {
        return storeOf(locations.setActive(id, false));
    }

    @PostMapping("/locations/{id}/activate")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public LocationResponse activateStore(@PathVariable UUID id) {
        return storeOf(locations.setActive(id, true));
    }

    /**
     * One store, and what is in it.
     *
     * <p>Deactivated stores are readable. Their stock is still real and somebody has to decide what
     * to do with it; hiding the page would make that harder, not safer.
     */
    @GetMapping("/locations/{id}")
    public StoreDetailResponse getStore(@PathVariable UUID id) {
        LocationRef store = locations.require(id);

        List<StockView> views = ledger.levelsAt(store.id());
        Map<UUID, ItemRef> itemsById =
                items.findAllById(views.stream().map(StockView::itemId).toList());

        List<StoreItemResponse> rows = new ArrayList<>();

        for (StockView view : views) {
            ItemRef item = itemsById.get(view.itemId());
            rows.add(
                    new StoreItemResponse(
                            view.itemId(),
                            item == null ? null : item.sku(),
                            item == null ? null : item.name(),
                            view.onHand(),
                            view.reserved(),
                            view.available(),
                            item == null ? 0 : item.reorderLevel()));
        }

        // Alphabetical by name, nulls last. A store list ordered by whatever the database returned
        // is unusable the moment it holds more than a screenful.
        rows.sort(Comparator.comparing(StoreItemResponse::itemName, Comparator.nullsLast(String::compareToIgnoreCase)));

        com.democode.mlmsittu.inventory.internal.location.Location entity =
                locations.findAllStores().stream()
                        .filter(candidate -> candidate.getId().equals(store.id()))
                        .findFirst()
                        .orElseThrow();

        return new StoreDetailResponse(
                store.id(),
                store.code(),
                store.name(),
                entity.getAddress(),
                store.isDefault(),
                store.active(),
                rows);
    }

    private LocationResponse storeOf(
            com.democode.mlmsittu.inventory.internal.location.Location store) {
        return new LocationResponse(
                store.getId(),
                store.getCode(),
                store.getName(),
                store.getAddress(),
                store.isDefaultLocation(),
                store.isActive());
    }

    // ------------------------------------------------------------------ stock

    /** Item name and SKU are joined in here rather than stored on the level — see the note below. */
    @GetMapping("/stock")
    public PagedResponse<StockLevelResponse> listStock(
            @RequestParam(required = false) UUID locationId) {

        List<StockView> views =
                locationId == null ? ledger.allLevels() : ledger.levelsAt(locationId);

        Map<UUID, ItemRef> itemsById =
                items.findAllById(views.stream().map(StockView::itemId).toList());

        return PagedResponse.of(
                views.stream()
                        .map(
                                view -> {
                                    ItemRef item = itemsById.get(view.itemId());
                                    int reorderLevel = item == null ? 0 : item.reorderLevel();
                                    return new StockLevelResponse(
                                            view.itemId(),
                                            view.locationId(),
                                            item == null ? null : item.sku(),
                                            item == null ? null : item.name(),
                                            view.onHand(),
                                            view.reserved(),
                                            view.available(),
                                            reorderLevel,
                                            reorderLevel > 0 && view.available() <= reorderLevel);
                                })
                        .toList());
    }

    /**
     * Stock totalled per item, with a per-store breakdown.
     *
     * <p>The screen's default view. Listing one row per (item, store) meant the same item appeared
     * several times with a slice of its quantity in each — which reads as a duplicate entry, and
     * made a perfectly healthy item look short in every store it was split across.
     */
    @GetMapping("/stock/by-item")
    public PagedResponse<StockByItemResponse> listStockByItem() {
        Map<UUID, LocationRef> storesById = new LinkedHashMap<>();
        locations.findAll().forEach(store -> storesById.put(store.id(), store));

        List<StockView> views = ledger.allLevels();
        Map<UUID, ItemRef> itemsById =
                items.findAllById(views.stream().map(StockView::itemId).toList());

        Map<UUID, List<StockView>> byItem = new LinkedHashMap<>();
        for (StockView view : views) {
            byItem.computeIfAbsent(view.itemId(), key -> new ArrayList<>()).add(view);
        }

        List<StockByItemResponse> rows = new ArrayList<>();
        for (Map.Entry<UUID, List<StockView>> entry : byItem.entrySet()) {
            ItemRef item = itemsById.get(entry.getKey());
            int reorderLevel = item == null ? 0 : item.reorderLevel();

            int onHand = 0;
            int reserved = 0;
            int available = 0;
            List<StockInStoreResponse> stores = new ArrayList<>();

            for (StockView view : entry.getValue()) {
                LocationRef store = storesById.get(view.locationId());
                // Only active stores count towards the totals, for the same reason the reorder
                // scan ignores them: stock nobody picks from is not cover. It still appears in the
                // breakdown, because it physically exists and somebody has to deal with it.
                if (store == null || store.active()) {
                    onHand += view.onHand();
                    reserved += view.reserved();
                    available += view.available();
                }
                stores.add(
                        new StockInStoreResponse(
                                view.locationId(),
                                store == null ? null : store.code(),
                                store == null ? null : store.name(),
                                store != null && store.active(),
                                view.onHand(),
                                view.reserved(),
                                view.available()));
            }

            stores.sort(
                    Comparator.comparing(
                            StockInStoreResponse::locationName,
                            Comparator.nullsLast(String::compareToIgnoreCase)));

            rows.add(
                    new StockByItemResponse(
                            entry.getKey(),
                            item == null ? null : item.sku(),
                            item == null ? null : item.name(),
                            onHand,
                            reserved,
                            available,
                            reorderLevel,
                            reorderLevel > 0 && available <= reorderLevel,
                            stores.size(),
                            stores));
        }

        rows.sort(
                Comparator.comparing(
                        StockByItemResponse::itemName,
                        Comparator.nullsLast(String::compareToIgnoreCase)));

        return PagedResponse.of(rows);
    }

    /**
     * Movement history, one page at a time (P7-03).
     *
     * <p>The ledger never deletes, so this is the endpoint that would eventually have returned a
     * response measured in megabytes. {@code cursor} comes from the previous response; absent
     * means start at the newest.
     */
    @GetMapping("/stock/movements")
    public PagedResponse<StockMovementView> listMovements(
            @RequestParam(required = false) UUID itemId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        int size = Cursor.clampLimit(limit, MOVEMENT_PAGE, MOVEMENT_PAGE);
        List<StockMovementView> fetched =
                ledger.movementPage(itemId, locationId, Cursor.decodeSequence(cursor), size + 1);

        if (fetched.size() <= size) {
            return PagedResponse.of(fetched);
        }
        List<StockMovementView> page = List.copyOf(fetched.subList(0, size));
        return PagedResponse.of(page, Cursor.encodeSequence(page.get(page.size() - 1).id()));
    }

    @PostMapping("/stock/adjustments")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public StockLevelResponse adjust(@Valid @RequestBody AdjustStockRequest body) {
        StockView view =
                adjustments.adjust(
                        body.itemId(),
                        body.locationId(),
                        body.qtyDelta(),
                        body.reason(),
                        body.note(),
                        currentUser.requireId());

        ItemRef item = items.findById(view.itemId()).orElse(null);
        int reorderLevel = item == null ? 0 : item.reorderLevel();
        return new StockLevelResponse(
                view.itemId(),
                view.locationId(),
                item == null ? null : item.sku(),
                item == null ? null : item.name(),
                view.onHand(),
                view.reserved(),
                view.available(),
                reorderLevel,
                reorderLevel > 0 && view.available() <= reorderLevel);
    }

    /**
     * Rebuilds the projection from the ledger.
     *
     * <p>{@code super_admin} only. It is a repair tool, and a repair tool that silently rewrites
     * stock figures is not something an ordinary clerk should be able to fire by accident.
     */
    @PostMapping("/stock/reconcile")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public StockReconciliationService.Report reconcile() {
        return reconciliation.reconcile();
    }

    // ------------------------------------------------------------------ reorder

    @PostMapping("/stock/reorder-scan")
    @PreAuthorize("hasAnyRole('INVENTORY_CLERK', 'PROCUREMENT_OFFICER', 'SUPER_ADMIN')")
    public ReorderScanService.ScanResult runReorderScan() {
        return reorderScan.scan();
    }

    @GetMapping("/stock/reorder-alerts")
    public PagedResponse<ReorderAlertResponse> listReorderAlerts(
            @RequestParam(defaultValue = "true") boolean openOnly) {

        var alerts = reorderScan.listAlerts(openOnly);
        Map<UUID, ItemRef> itemsById =
                items.findAllById(alerts.stream().map(alert -> alert.getItemId()).toList());

        return PagedResponse.of(
                alerts.stream()
                        .map(
                                alert -> {
                                    ItemRef item = itemsById.get(alert.getItemId());
                                    return ReorderAlertResponse.from(
                                            alert,
                                            item == null ? null : item.sku(),
                                            item == null ? null : item.name());
                                })
                        .toList());
    }
}
