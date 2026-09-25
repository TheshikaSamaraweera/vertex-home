package com.democode.mlmsittu.rewards.internal;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.catalogue.api.ItemSetRef;
import com.democode.mlmsittu.identity.api.UserDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.rewards.api.RewardStatus.PackLine;
import com.democode.mlmsittu.rewards.api.RewardStatus.Tracking;
import com.democode.mlmsittu.rewards.api.RewardStatus.TrackingStep;
import com.democode.mlmsittu.rewards.internal.domain.RewardEntitlement;
import com.democode.mlmsittu.rewards.internal.repo.RewardTrackingEventRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a tracked entitlement into the shape both audiences read — the office's tracking page and
 * the customer's dashboard.
 *
 * <p>Its own bean, depending on nothing that depends on {@code hierarchy}, so the customer-facing
 * {@link RewardGrantService} can use it without closing the loop that class exists to avoid.
 */
@Component
class TrackingDescriber {

    private final RewardTrackingEventRepository events;
    private final LocationDirectory locations;
    private final UserDirectory users;
    private final ItemCatalogue items;

    TrackingDescriber(
            RewardTrackingEventRepository events,
            LocationDirectory locations,
            UserDirectory users,
            ItemCatalogue items) {
        this.events = events;
        this.locations = locations;
        this.users = users;
        this.items = items;
    }

    /** Null for a pack that has not been issued: there is nothing to track yet. */
    Tracking describe(RewardEntitlement entitlement) {
        if (entitlement.getTrackingStage() == null) {
            return null;
        }
        LocationRef pickup = location(entitlement.getPickupLocationId());
        LocationRef handover = location(entitlement.getHandoverLocationId());

        List<TrackingStep> history =
                events.history(entitlement.getId()).stream()
                        .map(
                                event ->
                                        new TrackingStep(
                                                event.getStage(),
                                                event.getNote(),
                                                name(event.getActorId()),
                                                event.getAt()))
                        .toList();

        return new Tracking(
                entitlement.getTrackingNumber(),
                entitlement.getTrackingStage(),
                entitlement.getReceiveMethod(),
                entitlement.getPickupLocationId(),
                pickup == null ? null : pickup.name(),
                pickup == null ? null : pickup.address(),
                entitlement.getDeliveryAddress(),
                entitlement.getDeliveryContact(),
                entitlement.getReceiveMethodSetAt(),
                name(entitlement.getReceiveMethodSetBy()),
                entitlement.getHandoverLocationId(),
                handover == null ? null : handover.name(),
                handover == null ? null : handover.address(),
                entitlement.getCompletedAt(),
                name(entitlement.getCompletedBy()),
                history);
    }

    /** What is in the pack, by name and picture, sorted so it reads the same every time. */
    List<PackLine> lines(ItemSetRef pack) {
        if (pack == null) {
            return List.of();
        }
        Map<UUID, ItemRef> named = items.findAllById(pack.components().keySet());
        return pack.components().entrySet().stream()
                .map(
                        component -> {
                            ItemRef item = named.get(component.getKey());
                            return new PackLine(
                                    component.getKey(),
                                    item == null ? null : item.sku(),
                                    item == null ? null : item.name(),
                                    item == null ? null : item.description(),
                                    item == null ? null : item.imageId(),
                                    component.getValue());
                        })
                .sorted(Comparator.comparing(PackLine::name, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private LocationRef location(UUID id) {
        return id == null ? null : locations.findById(id).orElse(null);
    }

    private String name(UUID userId) {
        return Optional.ofNullable(userId)
                .flatMap(users::findById)
                .map(UserDirectory.UserRef::fullName)
                .orElse(null);
    }
}
