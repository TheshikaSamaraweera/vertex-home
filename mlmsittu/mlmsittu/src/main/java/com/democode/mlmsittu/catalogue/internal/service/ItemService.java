package com.democode.mlmsittu.catalogue.internal.service;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.repo.CategoryRepository;
import com.democode.mlmsittu.catalogue.internal.repo.ItemRepository;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import com.democode.mlmsittu.shared.api.Cursor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Item lifecycle (P2-01, P2-02) and the implementation of the module's published catalogue. */
@Service
public class ItemService implements ItemCatalogue {

    private final ItemRepository items;
    private final CategoryRepository categories;

    public ItemService(ItemRepository items, CategoryRepository categories) {
        this.items = items;
        this.categories = categories;
    }

    /**
     * Everything settable on an item.
     *
     * <p>Grouped into a record rather than passed as ten positional arguments. The create and
     * update signatures had already reached seven, and four of the fields are now money of the same
     * scale — exactly the shape of argument list where a transposition compiles cleanly and charges
     * the wrong number.
     */
    public record ItemDetails(
            String name,
            String description,
            UUID categoryId,
            BigDecimal unitCost,
            BigDecimal sellingPrice,
            BigDecimal retailPrice,
            BigDecimal wholesalePrice,
            int reorderLevel) {}

    // ------------------------------------------------------------------ published api

    @Override
    @Transactional(readOnly = true)
    public Optional<ItemRef> findById(UUID itemId) {
        return items.findById(itemId).map(Item::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ItemRef> findAllById(Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return items.findAllById(itemIds).stream()
                .map(Item::toRef)
                .collect(Collectors.toMap(ItemRef::id, Function.identity()));
    }

    /**
     * One page of items, keyed on where the last one stopped (P7-03).
     *
     * <p>Fetches {@code limit + 1} rows and hands them to {@link PagedResponse#page} — the extra
     * row is how "is there more?" is answered without a second query, and without a count that
     * could already be stale by the time it is read.
     *
     * @param cursor null for the first page
     */
    @Transactional(readOnly = true)
    public List<Item> page(boolean includeInactive, Cursor cursor, int limit) {
        Pageable window = PageRequest.of(0, limit + 1);
        return cursor == null
                ? items.firstPage(includeInactive, window)
                : items.pageAfter(includeInactive, cursor.sortKey(), cursor.id(), window);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemRef> findAllActive() {
        return items.findAllActiveOrdered().stream().map(Item::toRef).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemRef> findAll() {
        return items.findAllOrdered().stream().map(Item::toRef).toList();
    }

    // ------------------------------------------------------------------ commands

    /**
     * Creates the item, and nothing else.
     *
     * <p>Giving it an opening stock position is {@code ItemProvisioningService}'s job — the
     * catalogue knows what a product is, not where any of it happens to be.
     */
    @Transactional
    @Audited(action = "ITEM_CREATED", entityType = "item", auditFailures = true)
    public Item create(String sku, ItemDetails details) {
        assertCategoryExists(details.categoryId());

        Item item = new Item();
        item.setSku(normaliseSku(sku));
        item.setActive(true);
        apply(item, details);

        Item saved = persist(item);
        AuditContext.record(saved.getId(), null, snapshot(saved));
        return saved;
    }

    @Transactional
    @Audited(action = "ITEM_UPDATED", entityType = "item", auditFailures = true)
    public Item update(UUID id, ItemDetails details) {
        Item item = require(id);
        Map<String, Object> before = snapshot(item);
        assertCategoryExists(details.categoryId());

        apply(item, details);

        Item saved = items.save(item);
        AuditContext.record(id, before, snapshot(saved));
        return saved;
    }

    /**
     * Deactivation, not deletion.
     *
     * <p>An item is referenced by every stock movement and order line that ever touched it.
     * Deleting one would either orphan those records or cascade away history the business is
     * required to keep. Deactivating hides it from new orders and leaves the past intact.
     */
    @Transactional
    @Audited(action = "ITEM_ACTIVATION_CHANGED", entityType = "item", auditFailures = true)
    public Item setActive(UUID id, boolean active) {
        Item item = require(id);
        Map<String, Object> before = snapshot(item);
        item.setActive(active);
        Item saved = items.save(item);
        AuditContext.record(id, before, snapshot(saved));
        return saved;
    }

    @Transactional(readOnly = true)
    public Item get(UUID id) {
        return require(id);
    }

    /** Default listing hides deactivated items; {@code includeInactive} brings them back (P2-02). */
    @Transactional(readOnly = true)
    public List<Item> list(boolean includeInactive) {
        return includeInactive ? items.findAllOrdered() : items.findAllActiveOrdered();
    }

    // ------------------------------------------------------------------ helpers

    private void apply(Item item, ItemDetails details) {
        item.setName(details.name().trim());
        item.setDescription(details.description());
        item.setCategoryId(details.categoryId());
        item.setUnitCost(details.unitCost());
        item.setSellingPrice(details.sellingPrice());
        item.setRetailPrice(details.retailPrice());
        item.setWholesalePrice(details.wholesalePrice());
        item.setReorderLevel(details.reorderLevel());
    }

    private Item persist(Item item) {
        try {
            return items.saveAndFlush(item);
        } catch (DataIntegrityViolationException e) {
            // Checking existsBySku first would still race two concurrent creates. The unique
            // constraint is the actual guarantee; this turns its violation into a clean 409.
            if (mentions(e, "item_sku_key") || mentions(e, "sku")) {
                throw new ConflictException(
                        "DUPLICATE_SKU", "An item with that SKU already exists.");
            }
            throw e;
        }
    }

    private boolean mentions(DataIntegrityViolationException e, String fragment) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains(fragment);
    }

    /**
     * SKUs are quoted over the phone and typed off printed labels, where case is not reliably
     * preserved. Normalising on the way in means {@code slv-001} and {@code SLV-001} are one item
     * rather than two that drift apart.
     */
    private String normaliseSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }

    private void assertCategoryExists(UUID categoryId) {
        if (categoryId != null && !categories.existsById(categoryId)) {
            throw new NotFoundException("CATEGORY_NOT_FOUND", "No category with that id.");
        }
    }

    private Item require(UUID id) {
        return items.findById(id)
                .orElseThrow(() -> new NotFoundException("ITEM_NOT_FOUND", "No item with that id."));
    }

    private Map<String, Object> snapshot(Item item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sku", item.getSku());
        map.put("name", item.getName());
        map.put("categoryId", item.getCategoryId());
        map.put("unitCost", item.getUnitCost());
        map.put("sellingPrice", item.getSellingPrice());
        map.put("retailPrice", item.getRetailPrice());
        map.put("wholesalePrice", item.getWholesalePrice());
        map.put("reorderLevel", item.getReorderLevel());
        map.put("active", item.isActive());
        return map;
    }
}
