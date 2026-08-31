package com.democode.mlmsittu.catalogue.internal.service;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.catalogue.api.ItemSetCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemSetRef;
import com.democode.mlmsittu.catalogue.internal.domain.ItemSet;
import com.democode.mlmsittu.catalogue.internal.domain.ItemSetLine;
import com.democode.mlmsittu.catalogue.internal.repo.ItemSetRepository;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Item set composition (development plan P3-01). */
@Service
public class ItemSetService implements ItemSetCatalogue {

    private final ItemSetRepository sets;
    private final ItemCatalogue items;

    public ItemSetService(ItemSetRepository sets, ItemCatalogue items) {
        this.sets = sets;
        this.items = items;
    }

    /** One requested component. */
    public record ComponentRequest(UUID itemId, int quantity) {}

    // ------------------------------------------------------------------ published api

    @Override
    @Transactional(readOnly = true)
    public Optional<ItemSetRef> findById(UUID setId) {
        return sets.findById(setId).map(ItemSet::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemSetRef> findAllActive() {
        return sets.findAllActiveOrdered().stream().map(ItemSet::toRef).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemSetRef> findAll() {
        return sets.findAllOrdered().stream().map(ItemSet::toRef).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> contendedComponentIds() {
        return new HashSet<>(sets.findContendedComponentIds());
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    @Audited(action = "ITEM_SET_CREATED", entityType = "item_set", auditFailures = true)
    public ItemSet create(
            String code,
            String name,
            String description,
            BigDecimal setPrice,
            List<ComponentRequest> components) {

        ItemSet set = new ItemSet();
        set.setCode(code.trim().toUpperCase(Locale.ROOT));
        set.setName(name.trim());
        set.setDescription(description);
        set.setSetPrice(setPrice);
        set.setLines(buildLines(components));

        try {
            ItemSet saved = sets.saveAndFlush(set);
            AuditContext.record(saved.getId(), null, snapshot(saved));
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.ifConstraintIs(
                    e,
                    "item_set_code_key",
                    "DUPLICATE_ITEM_SET_CODE",
                    "A set with that code already exists.");
        }
    }

    @Transactional
    @Audited(action = "ITEM_SET_UPDATED", entityType = "item_set", auditFailures = true)
    public ItemSet update(
            UUID id,
            String name,
            String description,
            BigDecimal setPrice,
            List<ComponentRequest> components) {

        ItemSet set = require(id);
        Map<String, Object> before = snapshot(set);

        // Build first, so a bad request fails before anything is deleted.
        List<ItemSetLine> replacement = buildLines(components);

        set.setName(name.trim());
        set.setDescription(description);
        set.setSetPrice(setPrice);

        // The flush in the middle is load-bearing, and its absence was a real bug: editing a set
        // while keeping any of its items threw a duplicate-key violation on uq_item_set_line.
        //
        // Hibernate orders inserts before deletes within one flush, so the new line for an item
        // the set already had was inserted while the old row was still there. Keeping an item is
        // the *normal* edit — changing a price or a quantity — so this failed almost every time
        // somebody tried it.
        //
        // The identical mistake was fixed in PurchaseOrderService.replaceLines a few days earlier
        // and not looked for anywhere else. Both are @OneToMany with orphanRemoval and a unique
        // constraint on (parent, child); if a third such collection appears, it needs this too.
        set.getLines().clear();
        sets.saveAndFlush(set);

        set.getLines().addAll(replacement);

        ItemSet saved = sets.saveAndFlush(set);
        AuditContext.record(id, before, snapshot(saved));
        return saved;
    }

    @Transactional
    @Audited(action = "ITEM_SET_ACTIVATION_CHANGED", entityType = "item_set", auditFailures = true)
    public ItemSet setActive(UUID id, boolean active) {
        ItemSet set = require(id);
        Map<String, Object> before = snapshot(set);
        set.setActive(active);
        ItemSet saved = sets.save(set);
        AuditContext.record(id, before, snapshot(saved));
        return saved;
    }

    @Transactional(readOnly = true)
    public ItemSet get(UUID id) {
        return require(id);
    }

    @Transactional(readOnly = true)
    public List<ItemSet> list(boolean includeInactive) {
        return includeInactive ? sets.findAllOrdered() : sets.findAllActiveOrdered();
    }

    // ------------------------------------------------------------------ helpers

    private List<ItemSetLine> buildLines(List<ComponentRequest> components) {
        if (components == null || components.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "ITEM_SET_EMPTY",
                    "A set needs at least one component.");
        }

        Set<UUID> seen = new LinkedHashSet<>();
        for (ComponentRequest component : components) {
            if (!seen.add(component.itemId())) {
                throw new ConflictException(
                        "DUPLICATE_SET_COMPONENT",
                        "The same item appears twice. Combine it into one line with a higher"
                            + " quantity.");
            }
        }

        Map<UUID, ItemRef> known = items.findAllById(seen);
        List<ItemSetLine> lines = new ArrayList<>();

        for (ComponentRequest component : components) {
            ItemRef item = known.get(component.itemId());
            if (item == null) {
                NotFoundException notFound =
                        new NotFoundException("ITEM_NOT_FOUND", "No item with that id.");
                notFound.with("itemId", component.itemId());
                throw notFound;
            }
            if (!item.active()) {
                throw new ConflictException(
                        "ITEM_INACTIVE",
                        "A deactivated item cannot be part of a set: " + item.sku());
            }

            ItemSetLine line = new ItemSetLine();
            line.setItemId(item.id());
            line.setQuantity(component.quantity());
            lines.add(line);
        }
        return lines;
    }

    /**
     * Removes a set outright.
     *
     * <p>Refused once a set has been sold: {@code sales_order_line} points at it, and an order that
     * says "Starter Pack" has to keep meaning something years later. A set created by mistake and
     * never ordered deletes cleanly, which is what the button is for.
     *
     * <p>The set's own component lines go with it — they describe the set and have no meaning
     * without it. Nothing about stock moves: a set is a way of selling items, never a thing held in
     * inventory.
     */
    @Transactional
    @Audited(action = "ITEM_SET_DELETED", entityType = "item_set", auditFailures = true)
    public void delete(UUID id) {
        ItemSet set = require(id);

        long orderLines = sets.countSalesLinesFor(id);
        if (orderLines > 0) {
            ConflictException conflict =
                    new ConflictException(
                            "ITEM_SET_IN_USE",
                            "That set has been sold and cannot be deleted. Deactivate it instead —"
                                + " it stops new orders and leaves the history intact.");
            conflict.with("orderLines", orderLines);
            throw conflict;
        }

        AuditContext.record(id, snapshot(set), null);
        sets.delete(set);
    }

    private ItemSet require(UUID id) {
        return sets.findById(id)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "ITEM_SET_NOT_FOUND", "No item set with that id."));
    }

    private Map<String, Object> snapshot(ItemSet set) {
        return Map.of(
                "code", set.getCode(),
                "name", set.getName(),
                "setPrice", set.getSetPrice(),
                "active", set.isActive(),
                "components", set.componentMap());
    }
}
