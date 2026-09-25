package com.democode.mlmsittu.portal.internal;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.catalogue.api.ItemSetCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemSetRef;
import com.democode.mlmsittu.onboarding.api.RegistrationDirectory;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The item packs, as a customer is allowed to see them.
 *
 * <p><b>Open before registration.</b> Somebody who has just created an account has to choose a
 * pack on the registration form, and cannot choose well from a list of names. So this is the one
 * portal page that does not wait for approval.
 *
 * <p><b>Narrowed after choosing.</b> Once a registration names a pack, that pack is the customer's
 * and the others are shown locked: name, picture and price only, with their contents withheld.
 * The withholding happens here, on the server, rather than by fading cards in the browser — a
 * locked pack's contents are simply not in the response. A registration that was finally
 * rejected releases the choice, because that customer starts again.
 *
 * <p>Only active packs are listed, and each item's cost and stock are left out: a customer sees
 * what is in a pack and what the pack costs, not the business's margins or its warehouse.
 */
@Service
public class PackCatalogueService {

    private final ItemSetCatalogue sets;
    private final ItemCatalogue items;
    private final RegistrationDirectory registrations;

    public PackCatalogueService(
            ItemSetCatalogue sets, ItemCatalogue items, RegistrationDirectory registrations) {
        this.sets = sets;
        this.items = items;
        this.registrations = registrations;
    }

    /** One item inside a pack. */
    public record PackItem(
            UUID itemId,
            String sku,
            String name,
            String description,
            UUID imageId,
            int quantity) {}

    /**
     * @param selected this is the pack the customer's registration names
     * @param locked another pack was chosen; {@code description} and {@code items} are withheld
     */
    public record Pack(
            UUID id,
            String code,
            String name,
            String description,
            BigDecimal price,
            UUID imageId,
            boolean selected,
            boolean locked,
            List<PackItem> items) {}

    /** @param selectedPackId null until a registration names a pack */
    public record Catalogue(UUID selectedPackId, List<Pack> packs) {}

    @Transactional(readOnly = true)
    public Catalogue forCustomer(UUID userId) {
        UUID selected = selectedPack(userId).orElse(null);

        List<ItemSetRef> active = sets.findAllActive();
        Map<UUID, ItemRef> itemsById =
                items.findAllById(
                        active.stream().flatMap(set -> set.components().keySet().stream()).toList());

        List<Pack> packs =
                active.stream()
                        // The chosen pack first, then by name: it is the one they came to see.
                        .sorted(
                                Comparator.comparing((ItemSetRef set) -> !set.id().equals(selected))
                                        .thenComparing(ItemSetRef::name, String.CASE_INSENSITIVE_ORDER))
                        .map(set -> packFor(set, selected, itemsById))
                        .toList();

        return new Catalogue(selected, packs);
    }

    private Pack packFor(ItemSetRef set, UUID selected, Map<UUID, ItemRef> itemsById) {
        boolean isSelected = set.id().equals(selected);
        boolean locked = selected != null && !isSelected;
        List<PackItem> contents =
                locked
                        ? List.of()
                        : set.components().entrySet().stream()
                                .map(
                                        entry -> {
                                            ItemRef item = itemsById.get(entry.getKey());
                                            return item == null
                                                    ? null
                                                    : new PackItem(
                                                            item.id(),
                                                            item.sku(),
                                                            item.name(),
                                                            item.description(),
                                                            item.imageId(),
                                                            entry.getValue());
                                        })
                                .filter(java.util.Objects::nonNull)
                                .sorted(Comparator.comparing(PackItem::name, String.CASE_INSENSITIVE_ORDER))
                                .toList();
        return new Pack(
                set.id(),
                set.code(),
                set.name(),
                locked ? null : set.description(),
                set.setPrice(),
                set.imageId(),
                isSelected,
                locked,
                contents);
    }

    /** The pack on the customer's latest registration, unless that registration was rejected. */
    private Optional<UUID> selectedPack(UUID userId) {
        return registrations
                .latestFor(userId)
                .filter(registration -> !"rejected".equals(registration.status()))
                .map(RegistrationDirectory.RegistrationSnapshot::itemSetId);
    }
}
