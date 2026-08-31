package com.democode.mlmsittu.commerce.internal.service;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.commerce.internal.domain.ItemSupplierPrice;
import com.democode.mlmsittu.commerce.internal.repo.ItemSupplierPriceRepository;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each supplier charges for each item.
 *
 * <p>Feeds purchase order lines: ordering from a supplier who has quoted for an item fills in that
 * quote, rather than the catalogue's own unit cost. The catalogue cost stays as the fallback for
 * items nobody has quoted for, so nothing breaks for the items that predate this.
 */
@Service
public class SupplierPriceService {

    private final ItemSupplierPriceRepository prices;
    private final SupplierService suppliers;
    private final ItemCatalogue items;

    public SupplierPriceService(
            ItemSupplierPriceRepository prices, SupplierService suppliers, ItemCatalogue items) {
        this.prices = prices;
        this.suppliers = suppliers;
        this.items = items;
    }

    /**
     * Records or replaces a supplier's price for an item.
     *
     * <p>Upsert rather than create-then-update, because "what does this supplier charge" has one
     * answer and a caller re-quoting should not have to know whether they are the first to do so.
     */
    @Transactional
    @Audited(action = "SUPPLIER_PRICE_SET", entityType = "item_supplier_price", auditFailures = true)
    public ItemSupplierPrice set(
            UUID itemId, UUID supplierId, BigDecimal price, String note, UUID actorId) {

        items.findById(itemId)
                .orElseThrow(() -> new NotFoundException("ITEM_NOT_FOUND", "No item with that id."));
        // Deactivated suppliers are refused: a price you cannot order against is misinformation.
        suppliers.requireSelectable(supplierId);

        if (price == null || price.signum() < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_PRICE", "A price cannot be negative.");
        }

        ItemSupplierPrice row =
                prices.findByItemIdAndSupplierId(itemId, supplierId)
                        .orElseGet(
                                () -> {
                                    ItemSupplierPrice fresh = new ItemSupplierPrice();
                                    fresh.setItemId(itemId);
                                    fresh.setSupplierId(supplierId);
                                    fresh.setCreatedBy(actorId);
                                    return fresh;
                                });

        Map<String, Object> before = row.getId() == null ? null : snapshot(row);

        row.setPrice(price);
        row.setNote(note);

        ItemSupplierPrice saved = prices.save(row);
        AuditContext.record(saved.getId(), before, snapshot(saved));
        return saved;
    }

    @Transactional
    @Audited(action = "SUPPLIER_PRICE_REMOVED", entityType = "item_supplier_price")
    public void remove(UUID itemId, UUID supplierId) {
        ItemSupplierPrice row =
                prices.findByItemIdAndSupplierId(itemId, supplierId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "SUPPLIER_PRICE_NOT_FOUND",
                                                "That supplier has no price for that item."));
        AuditContext.record(row.getId(), snapshot(row), null);
        prices.delete(row);
    }

    @Transactional(readOnly = true)
    public List<ItemSupplierPrice> forItem(UUID itemId) {
        return prices.findForItem(itemId);
    }

    /**
     * The prices a supplier quotes for a set of items, keyed by item.
     *
     * <p>Used when building a purchase order, which is why it is a bulk lookup rather than one call
     * per line.
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> quotedBy(UUID supplierId, List<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return prices.findForSupplierAndItems(supplierId, itemIds).stream()
                .collect(
                        Collectors.toMap(
                                ItemSupplierPrice::getItemId, ItemSupplierPrice::getPrice));
    }

    @Transactional(readOnly = true)
    public Optional<BigDecimal> quotedBy(UUID supplierId, UUID itemId) {
        return prices.findByItemIdAndSupplierId(itemId, supplierId)
                .map(ItemSupplierPrice::getPrice);
    }

    private Map<String, Object> snapshot(ItemSupplierPrice row) {
        Map<String, Object> map = new HashMap<>();
        map.put("itemId", row.getItemId());
        map.put("supplierId", row.getSupplierId());
        map.put("price", row.getPrice());
        map.put("note", row.getNote());
        return map;
    }
}
