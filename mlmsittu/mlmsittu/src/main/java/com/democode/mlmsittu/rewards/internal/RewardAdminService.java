package com.democode.mlmsittu.rewards.internal;

import com.democode.mlmsittu.catalogue.api.ItemCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.catalogue.api.ItemSetCatalogue;
import com.democode.mlmsittu.catalogue.api.ItemSetRef;
import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import com.democode.mlmsittu.identity.api.UserDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockPosting;
import com.democode.mlmsittu.inventory.api.StockView;
import com.democode.mlmsittu.rewards.internal.domain.RewardEntitlement;
import com.democode.mlmsittu.rewards.internal.repo.RewardEntitlementRepository;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import com.democode.mlmsittu.shared.notify.NotificationSender;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administrator's side of the reward mechanic: who is waiting, can it be filled, and handing
 * it over.
 *
 * <p>Nothing here happens on its own. A distributor becoming eligible creates a row and sends a
 * nudge; the goods do not move until a named administrator issues them, out of a named store. That
 * is what keeps §5's "no automated disbursement" true once the mechanic actually grants something,
 * and it is why the issue path writes an audit record and a stock movement rather than just a
 * status change.
 */
@Service
public class RewardAdminService {

    private static final Logger log = LoggerFactory.getLogger(RewardAdminService.class);

    private final RewardEntitlementRepository entitlements;
    private final ItemSetCatalogue itemSets;
    private final ItemCatalogue items;
    private final LocationDirectory locations;
    private final StockLedger ledger;
    private final ReferralHierarchy hierarchy;
    private final UserDirectory users;
    private final NotificationSender notifications;

    public RewardAdminService(
            RewardEntitlementRepository entitlements,
            ItemSetCatalogue itemSets,
            ItemCatalogue items,
            LocationDirectory locations,
            StockLedger ledger,
            ReferralHierarchy hierarchy,
            UserDirectory users,
            NotificationSender notifications) {
        this.entitlements = entitlements;
        this.itemSets = itemSets;
        this.items = items;
        this.locations = locations;
        this.ledger = ledger;
        this.hierarchy = hierarchy;
        this.users = users;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ views

    /**
     * One component of the pack, and whether this particular store can supply it.
     *
     * <p>Named for the pack rather than generically: {@code inventory} already publishes a
     * {@code PackComponent} about item sets, and the generated OpenAPI schema is keyed by
     * simple class name — two records sharing one would silently overwrite each other and hand the
     * frontend the wrong shape.
     */
    public record PackComponent(
            UUID itemId, String sku, String itemName, int required, int available, boolean enough) {}

    /**
     * @param canFulfilWholePack true only when every component is available here in full — a
     *     half-filled pack is not a pack, so a store either supplies all of it or none
     */
    public record PackStoreOption(
            UUID locationId,
            String locationCode,
            String locationName,
            boolean canFulfilWholePack,
            List<PackComponent> components) {}

    /**
     * @param stores every active store, whether or not it can supply the pack — an administrator
     *     needs to see the ones that fall short as well, or "why can I not issue this" has no
     *     answer on the screen
     */
    public record RewardEntitlementView(
            UUID id,
            String status,
            java.time.Instant becameEligibleAt,
            java.time.Instant issuedAt,
            UUID distributorId,
            UUID userId,
            String businessId,
            String distributorName,
            String distributorEmail,
            String distributorMobile,
            UUID itemSetId,
            String itemSetCode,
            String itemSetName,
            boolean anyStoreCanFulfil,
            UUID issuedFromLocationId,
            String issuedFromLocationName,
            String issuedByName,
            String note,
            List<PackStoreOption> stores) {}

    @Transactional(readOnly = true)
    public List<RewardEntitlementView> list(String status) {
        List<RewardEntitlement> rows =
                status == null || status.isBlank()
                        ? entitlements.findAllNewestFirst()
                        : entitlements.findByStatus(status);
        return rows.stream().map(this::describe).toList();
    }

    @Transactional(readOnly = true)
    public RewardEntitlementView get(UUID id) {
        return describe(require(id));
    }

    @Transactional(readOnly = true)
    public long countWaiting() {
        return entitlements.countByStatus(RewardEntitlement.ELIGIBLE);
    }

    /** What one distributor can see about their own pack. Null when they have no entitlement. */
    @Transactional(readOnly = true)
    public Optional<RewardEntitlement> forDistributor(UUID distributorId) {
        return entitlements.findByDistributorId(distributorId);
    }

    // ------------------------------------------------------------------ issuing

    /**
     * Hands the pack over and takes the stock out of one store.
     *
     * <p>One store, not several. Splitting a pack across two stores would mean somebody physically
     * collecting from two places, and the client's warehouse does not work that way — so the store
     * either has all of it or the issue is refused with {@code PACK_NOT_AVAILABLE} and the
     * shortfall named.
     *
     * <p>The row is locked first. Two administrators issuing the same pack at once would otherwise
     * both read {@code eligible}, both post movements, and the distributor would collect twice.
     */
    @Transactional
    @Audited(action = "REWARD_PACK_ISSUED", entityType = "reward_entitlement", auditFailures = true)
    public RewardEntitlementView issue(UUID entitlementId, UUID locationId, String note, UUID actorId) {
        RewardEntitlement entitlement =
                entitlements
                        .lockForUpdate(entitlementId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "REWARD_ENTITLEMENT_NOT_FOUND",
                                                "No entitlement with that id."));

        if (entitlement.isIssued()) {
            ConflictException conflict =
                    new ConflictException(
                            "REWARD_ALREADY_ISSUED", "This pack has already been issued.");
            conflict.with("issuedAt", entitlement.getIssuedAt());
            throw conflict;
        }
        if (!RewardEntitlement.ELIGIBLE.equals(entitlement.getStatus())) {
            ConflictException conflict =
                    new ConflictException(
                            "REWARD_NOT_ISSUABLE", "This entitlement is not waiting to be issued.");
            conflict.with("entitlementStatus", entitlement.getStatus());
            throw conflict;
        }

        LocationRef store = requireStore(locationId);
        ItemSetRef pack = requirePack(entitlement.getItemSetId());

        PackStoreOption option = availabilityAt(store, pack);
        if (!option.canFulfilWholePack()) {
            List<String> short_ =
                    option.components().stream()
                            .filter(component -> !component.enough())
                            .map(
                                    component ->
                                            component.sku()
                                                    + " (need "
                                                    + component.required()
                                                    + ", have "
                                                    + component.available()
                                                    + ")")
                            .toList();
            ConflictException conflict =
                    new ConflictException(
                            "PACK_NOT_AVAILABLE",
                            "This store cannot supply the whole pack: " + String.join(", ", short_));
            conflict.with("locationId", store.id());
            conflict.with("locationName", store.name());
            conflict.with("shortOf", short_);
            throw conflict;
        }

        String movementNote =
                "Reward pack " + pack.code() + " issued to " + describeHolder(entitlement);

        List<StockPosting> postings = new ArrayList<>();
        pack.components()
                .forEach(
                        (itemId, quantity) ->
                                postings.add(
                                        StockPosting.rewardIssue(
                                                itemId,
                                                store.id(),
                                                quantity,
                                                entitlement.getId(),
                                                movementNote,
                                                actorId)));

        // One call, so the whole pack moves or none of it does. The ledger locks rows in a
        // deterministic order, which is what stops two concurrent issues deadlocking over the same
        // two items in opposite sequence.
        ledger.postAll(postings);

        entitlement.markIssued(store.id(), actorId, note);
        RewardEntitlement saved = entitlements.save(entitlement);

        AuditContext.record(
                saved.getId(),
                Map.of("status", RewardEntitlement.ELIGIBLE),
                Map.of(
                        "status", RewardEntitlement.ISSUED,
                        "distributorId", saved.getDistributorId(),
                        "itemSetId", saved.getItemSetId(),
                        "locationId", store.id(),
                        "components", pack.components().size()));

        notifyDistributor(saved, pack, store);
        return describe(saved);
    }

    // ------------------------------------------------------------------ helpers

    private void notifyDistributor(
            RewardEntitlement entitlement, ItemSetRef pack, LocationRef store) {
        // Distributor id, then the account behind it. They are different identifiers and mixing
        // them up would send somebody else's pack notice to the wrong person.
        Optional<UserDirectory.UserRef> holder;
        try {
            holder = users.findById(hierarchy.get(entitlement.getDistributorId()).userId());
        } catch (RuntimeException missing) {
            log.warn(
                    "Issued pack {} but could not resolve the distributor to notify",
                    entitlement.getId());
            return;
        }

        holder.ifPresent(
                user -> {
                    String body =
                            """
                            Your item pack has been issued.

                            Pack  : %s — %s
                            From  : %s

                            It is ready to collect. You can see this on your Rewards page in the
                            distributor portal.
                            """
                                    .formatted(pack.code(), pack.name(), store.name());
                    try {
                        notifications.sendEmail(user.email(), "Your item pack has been issued", body);
                    } catch (RuntimeException failure) {
                        // The pack is already handed over and the row already says so. Failing the
                        // transaction now would un-record goods that physically left the building.
                        log.warn("Could not tell {} their pack was issued", user.email(), failure);
                    }
                });
    }

    private String describeHolder(RewardEntitlement entitlement) {
        try {
            DistributorNode node = hierarchy.get(entitlement.getDistributorId());
            return node.businessId() + " " + node.fullName();
        } catch (RuntimeException missing) {
            return "distributor " + entitlement.getDistributorId();
        }
    }

    private RewardEntitlementView describe(RewardEntitlement entitlement) {
        DistributorNode node = null;
        try {
            node = hierarchy.get(entitlement.getDistributorId());
        } catch (RuntimeException missing) {
            log.warn("Entitlement {} points at a distributor that is gone", entitlement.getId());
        }

        UserDirectory.UserRef account =
                node == null ? null : users.findById(node.userId()).orElse(null);

        ItemSetRef pack = itemSets.findById(entitlement.getItemSetId()).orElse(null);

        List<PackStoreOption> stores = new ArrayList<>();
        if (pack != null && !entitlement.isIssued()) {
            for (LocationRef store : locations.findAllActive()) {
                stores.add(availabilityAt(store, pack));
            }
            // The stores that can actually fill it first — that is the decision being made.
            stores.sort(
                    Comparator.comparing(PackStoreOption::canFulfilWholePack)
                            .reversed()
                            .thenComparing(
                                    PackStoreOption::locationName,
                                    Comparator.nullsLast(String::compareToIgnoreCase)));
        }

        LocationRef issuedFrom =
                entitlement.getIssuedFromLocationId() == null
                        ? null
                        : locations.findById(entitlement.getIssuedFromLocationId()).orElse(null);

        return new RewardEntitlementView(
                entitlement.getId(),
                entitlement.getStatus(),
                entitlement.getBecameEligibleAt(),
                entitlement.getIssuedAt(),
                entitlement.getDistributorId(),
                node == null ? null : node.userId(),
                node == null ? null : node.businessId(),
                node == null ? null : node.fullName(),
                account == null ? null : account.email(),
                account == null ? null : account.mobile(),
                entitlement.getItemSetId(),
                pack == null ? null : pack.code(),
                pack == null ? null : pack.name(),
                stores.stream().anyMatch(PackStoreOption::canFulfilWholePack),
                entitlement.getIssuedFromLocationId(),
                issuedFrom == null ? null : issuedFrom.name(),
                entitlement.getIssuedBy() == null
                        ? null
                        : users.findById(entitlement.getIssuedBy())
                                .map(UserDirectory.UserRef::fullName)
                                .orElse(null),
                entitlement.getNote(),
                stores);
    }

    /**
     * Measured against <b>available</b>, not on hand.
     *
     * <p>Units already reserved for a sales order are promised to somebody else. Counting them as
     * cover would let a reward pack be issued out of stock that is about to ship, and the shortfall
     * would surface as a failed customer order rather than here, where it can be dealt with.
     */
    private PackStoreOption availabilityAt(LocationRef store, ItemSetRef pack) {
        Map<UUID, StockView> here = new LinkedHashMap<>();
        ledger.levelsAt(store.id()).forEach(view -> here.put(view.itemId(), view));

        Map<UUID, ItemRef> named = items.findAllById(pack.components().keySet());

        List<PackComponent> components = new ArrayList<>();
        boolean whole = !pack.components().isEmpty();

        for (Map.Entry<UUID, Integer> component : pack.components().entrySet()) {
            ItemRef item = named.get(component.getKey());
            StockView view = here.get(component.getKey());
            int available = view == null ? 0 : view.available();
            int required = component.getValue();
            boolean enough = available >= required;
            whole = whole && enough;

            components.add(
                    new PackComponent(
                            component.getKey(),
                            item == null ? null : item.sku(),
                            item == null ? null : item.name(),
                            required,
                            available,
                            enough));
        }

        components.sort(
                Comparator.comparing(
                        PackComponent::sku, Comparator.nullsLast(String::compareToIgnoreCase)));

        return new PackStoreOption(store.id(), store.code(), store.name(), whole, components);
    }

    private RewardEntitlement require(UUID id) {
        return entitlements
                .findById(id)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "REWARD_ENTITLEMENT_NOT_FOUND",
                                        "No entitlement with that id."));
    }

    private LocationRef requireStore(UUID locationId) {
        if (locationId == null) {
            return locations.defaultLocation();
        }
        return locations
                .findById(locationId)
                .orElseThrow(
                        () -> new NotFoundException("LOCATION_NOT_FOUND", "No store with that id."));
    }

    private ItemSetRef requirePack(UUID itemSetId) {
        return itemSets
                .findById(itemSetId)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "ITEM_SET_NOT_FOUND",
                                        "The pack this entitlement names no longer exists."));
    }
}
