package com.democode.mlmsittu.rewards.internal;

import com.democode.mlmsittu.hierarchy.api.ReferralHierarchy;
import com.democode.mlmsittu.identity.api.PasswordConfirmation;
import com.democode.mlmsittu.identity.api.UserDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.rewards.api.RewardDelivery;
import com.democode.mlmsittu.rewards.internal.RewardAdminService.RewardEntitlementView;
import com.democode.mlmsittu.rewards.internal.domain.RewardEntitlement;
import com.democode.mlmsittu.rewards.internal.domain.RewardTrackingEvent;
import com.democode.mlmsittu.rewards.internal.repo.RewardEntitlementRepository;
import com.democode.mlmsittu.rewards.internal.repo.RewardTrackingEventRepository;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import com.democode.mlmsittu.shared.notify.Notifications;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An issued pack's journey from the warehouse to the customer.
 *
 * <h2>The stages</h2>
 *
 * {@code awaiting_method → preparing → dispatched → completed}. Issuing starts it; the customer
 * choosing how to receive it moves it to {@code preparing}; the office moves it on from there. The
 * last stage is final and closes the customer's business account — nothing moves a completed pack.
 *
 * <h2>The receiving method</h2>
 *
 * Chosen once by the customer, or added by the office if the customer has not. Changing one that
 * is already set is an office-only override of somebody else's choice, so it takes the
 * administrator's password as well as their session, and is audited either way.
 */
@Service
public class RewardTrackingService implements RewardDelivery {

    private static final Logger log = LoggerFactory.getLogger(RewardTrackingService.class);

    /** The stages the office may move a pack to by hand. Completion has its own path. */
    private static final Set<String> MANUAL_STAGES =
            Set.of(RewardEntitlement.PREPARING, RewardEntitlement.DISPATCHED);

    /** Digits, spaces, dashes and one leading plus: enough for any phone number a driver dials. */
    private static final Pattern CONTACT = Pattern.compile("\\+?[0-9 ()-]{7,20}");

    private final RewardEntitlementRepository entitlements;
    private final RewardTrackingEventRepository events;
    private final RewardAdminService admin;
    private final LocationDirectory locations;
    private final ReferralHierarchy hierarchy;
    private final UserDirectory users;
    private final PasswordConfirmation passwords;
    private final Notifications inApp;

    public RewardTrackingService(
            RewardEntitlementRepository entitlements,
            RewardTrackingEventRepository events,
            RewardAdminService admin,
            LocationDirectory locations,
            ReferralHierarchy hierarchy,
            UserDirectory users,
            PasswordConfirmation passwords,
            Notifications inApp) {
        this.entitlements = entitlements;
        this.events = events;
        this.admin = admin;
        this.locations = locations;
        this.hierarchy = hierarchy;
        this.users = users;
        this.passwords = passwords;
        this.inApp = inApp;
    }

    // ================================================================== customer

    @Override
    @Transactional(readOnly = true)
    public List<PickupPoint> pickupPoints() {
        return locations.findAllActive().stream()
                .map(store -> new PickupPoint(store.id(), store.code(), store.name(), store.address()))
                .toList();
    }

    @Override
    @Transactional
    @Audited(action = "REWARD_RECEIVE_METHOD_CHOSEN", entityType = "reward_entitlement")
    public void chooseReceiveMethod(UUID distributorId, ReceiveMethodChoice choice) {
        UUID id =
                entitlements
                        .findByDistributorId(distributorId)
                        .map(RewardEntitlement::getId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "REWARD_NOT_FOUND",
                                                "You do not have an item pack yet."));
        RewardEntitlement entitlement = lockTracked(id);

        if (entitlement.hasReceiveMethod()) {
            throw new ConflictException(
                    "RECEIVE_METHOD_LOCKED",
                    "You have already chosen how to receive your pack. Contact the office to"
                            + " change it.");
        }

        String summary = apply(entitlement, choice, null);
        String stageBefore = entitlement.getTrackingStage();
        entitlement.setTrackingStage(RewardEntitlement.PREPARING);
        entitlements.save(entitlement);
        events.save(
                new RewardTrackingEvent(
                        entitlement.getId(), RewardEntitlement.PREPARING, "You chose " + summary, null));

        AuditContext.record(
                entitlement.getId(),
                Map.of("stage", stageBefore),
                Map.of("stage", RewardEntitlement.PREPARING, "receiveMethod", choice.method()));

        notifyOffice(entitlement, summary);
    }

    // ================================================================== office

    /**
     * Adds or changes the receiving method.
     *
     * @param password required only when a method is already set — overriding a choice somebody
     *     else made
     */
    @Transactional
    @Audited(
            action = "REWARD_RECEIVE_METHOD_SET",
            entityType = "reward_entitlement",
            auditFailures = true)
    public RewardEntitlementView setReceiveMethod(
            UUID entitlementId, ReceiveMethodChoice choice, String password, UUID actorId) {
        RewardEntitlement entitlement = lockTracked(entitlementId);

        boolean changing = entitlement.hasReceiveMethod();
        if (changing) {
            // Before anything is validated or written: a wrong password must change nothing.
            passwords.confirm(actorId, password);
        }

        Map<String, Object> before = receivingOf(entitlement);
        String summary = apply(entitlement, choice, actorId);

        if (RewardEntitlement.AWAITING_METHOD.equals(entitlement.getTrackingStage())) {
            entitlement.setTrackingStage(RewardEntitlement.PREPARING);
        }
        entitlements.save(entitlement);
        events.save(
                new RewardTrackingEvent(
                        entitlement.getId(),
                        entitlement.getTrackingStage(),
                        (changing ? "Receiving method changed to " : "Receiving method set to ")
                                + summary,
                        actorId));

        AuditContext.record(entitlement.getId(), before, receivingOf(entitlement));

        notifyCustomer(
                entitlement,
                changing ? "How you receive your pack has changed" : "How you receive your pack",
                "The office has set it to " + summary + ".");
        return admin.get(entitlement.getId());
    }

    /** Moves a pack to {@code preparing} or {@code dispatched}. */
    @Transactional
    @Audited(action = "REWARD_STAGE_CHANGED", entityType = "reward_entitlement")
    public RewardEntitlementView changeStage(
            UUID entitlementId, String stage, String note, UUID actorId) {
        if (!MANUAL_STAGES.contains(stage)) {
            throw invalid(
                    "INVALID_STAGE",
                    "A pack can be moved to preparing or dispatched here. Completing it has its own"
                            + " step.");
        }
        RewardEntitlement entitlement = lockTracked(entitlementId);
        if (!entitlement.hasReceiveMethod()) {
            throw new ConflictException(
                    "RECEIVE_METHOD_REQUIRED",
                    "Set how the customer will receive this pack before moving it on.");
        }
        String before = entitlement.getTrackingStage();
        if (stage.equals(before)) {
            throw new ConflictException("ALREADY_AT_STAGE", "The pack is already at that stage.");
        }

        entitlement.setTrackingStage(stage);
        entitlements.save(entitlement);
        String label = label(stage, entitlement);
        events.save(
                new RewardTrackingEvent(
                        entitlement.getId(), stage, blankToNull(note, label), actorId));

        AuditContext.record(entitlement.getId(), Map.of("stage", before), Map.of("stage", stage));

        notifyCustomer(entitlement, label, note == null || note.isBlank() ? null : note);
        return admin.get(entitlement.getId());
    }

    /**
     * The last stage: handed over at a named warehouse. Final — it closes the customer's business
     * account, and nothing moves a completed pack afterwards.
     */
    @Transactional
    @Audited(action = "REWARD_PACK_COMPLETED", entityType = "reward_entitlement")
    public RewardEntitlementView complete(
            UUID entitlementId, UUID locationId, String note, UUID actorId) {
        RewardEntitlement entitlement = lockTracked(entitlementId);
        if (!entitlement.hasReceiveMethod()) {
            throw new ConflictException(
                    "RECEIVE_METHOD_REQUIRED",
                    "Set how the customer will receive this pack before completing it.");
        }
        LocationRef warehouse = requireActiveStore(locationId);

        String before = entitlement.getTrackingStage();
        entitlement.markCompleted(warehouse.id(), actorId);
        entitlements.save(entitlement);
        String label = label(RewardEntitlement.COMPLETED, entitlement);
        events.save(
                new RewardTrackingEvent(
                        entitlement.getId(),
                        RewardEntitlement.COMPLETED,
                        blankToNull(note, label + " — " + warehouse.name()),
                        actorId));

        AuditContext.record(
                entitlement.getId(),
                Map.of("stage", before),
                Map.of("stage", RewardEntitlement.COMPLETED, "warehouseId", warehouse.id()));

        notifyCustomer(
                entitlement,
                label,
                "Your item pack journey is complete. Thank you for being with us — you can register"
                        + " again for another pack.");
        return admin.get(entitlement.getId());
    }

    // ================================================================== helpers

    /** Locks the row, and refuses anything not issued yet or already finished. */
    private RewardEntitlement lockTracked(UUID entitlementId) {
        RewardEntitlement entitlement =
                entitlements
                        .lockForUpdate(entitlementId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "REWARD_ENTITLEMENT_NOT_FOUND",
                                                "No entitlement with that id."));
        if (entitlement.getTrackingStage() == null) {
            throw new ConflictException(
                    "REWARD_NOT_ISSUED", "This pack has not been issued yet.");
        }
        if (entitlement.isCompleted()) {
            throw new ConflictException(
                    "REWARD_COMPLETED", "This pack has already been handed over.");
        }
        return entitlement;
    }

    /** Validates and writes the method; returns a line saying what was chosen. */
    private String apply(RewardEntitlement entitlement, ReceiveMethodChoice choice, UUID actorId) {
        if (choice == null || choice.method() == null) {
            throw invalid("RECEIVE_METHOD_REQUIRED", "Choose pickup or delivery.");
        }
        return switch (choice.method()) {
            case RewardEntitlement.PICKUP -> {
                LocationRef store = requireActiveStore(choice.pickupLocationId());
                entitlement.setReceiving(RewardEntitlement.PICKUP, store.id(), null, null, actorId);
                yield "pickup from " + store.name();
            }
            case RewardEntitlement.DELIVERY -> {
                String address = trim(choice.deliveryAddress());
                String contact = trim(choice.deliveryContact());
                if (address == null || address.length() > 500) {
                    throw invalid(
                            "DELIVERY_ADDRESS_REQUIRED",
                            "Enter the delivery address (up to 500 characters).");
                }
                if (contact == null || !CONTACT.matcher(contact).matches()) {
                    throw invalid(
                            "DELIVERY_CONTACT_INVALID", "Enter a contact number for the delivery.");
                }
                entitlement.setReceiving(RewardEntitlement.DELIVERY, null, address, contact, actorId);
                yield "delivery to " + address;
            }
            default -> throw invalid("RECEIVE_METHOD_INVALID", "Choose pickup or delivery.");
        };
    }

    private LocationRef requireActiveStore(UUID locationId) {
        if (locationId == null) {
            throw invalid("WAREHOUSE_REQUIRED", "Choose a warehouse.");
        }
        return locations
                .findById(locationId)
                .filter(LocationRef::active)
                .orElseThrow(
                        () -> new NotFoundException("LOCATION_NOT_FOUND", "No such warehouse."));
    }

    /** What the customer reads for each stage — the words depend on pickup or delivery. */
    static String label(String stage, RewardEntitlement entitlement) {
        boolean pickup = RewardEntitlement.PICKUP.equals(entitlement.getReceiveMethod());
        return switch (stage) {
            case RewardEntitlement.AWAITING_METHOD -> "Waiting for a receiving method";
            case RewardEntitlement.PREPARING -> "Your pack is being prepared";
            case RewardEntitlement.DISPATCHED ->
                    pickup ? "Your pack is ready for pickup" : "Your pack is out for delivery";
            case RewardEntitlement.COMPLETED ->
                    pickup ? "Your pack has been picked up" : "Your pack has been delivered";
            default -> stage;
        };
    }

    private static Map<String, Object> receivingOf(RewardEntitlement entitlement) {
        // HashMap, not Map.of: most of these are null for any one method.
        Map<String, Object> values = new HashMap<>();
        values.put("receiveMethod", entitlement.getReceiveMethod());
        values.put("pickupLocationId", entitlement.getPickupLocationId());
        values.put("deliveryAddress", entitlement.getDeliveryAddress());
        values.put("deliveryContact", entitlement.getDeliveryContact());
        return values;
    }

    private void notifyCustomer(RewardEntitlement entitlement, String title, String body) {
        try {
            UUID userId = hierarchy.get(entitlement.getDistributorId()).userId();
            inApp.raise(
                    userId,
                    Notifications.REWARD_TRACKING,
                    title,
                    body == null
                            ? "Tracking number " + entitlement.getTrackingNumber() + "."
                            : body,
                    "/portal");
        } catch (RuntimeException failure) {
            // The stage has moved and the row says so; a missing notice must not undo that.
            log.warn("Could not tell the customer about pack {}", entitlement.getId(), failure);
        }
    }

    private void notifyOffice(RewardEntitlement entitlement, String summary) {
        Map<UUID, UserDirectory.UserRef> recipients = new LinkedHashMap<>();
        users.findByRole("SUPER_ADMIN").forEach(user -> recipients.put(user.id(), user));
        users.findByRole("ADMIN").forEach(user -> recipients.put(user.id(), user));
        String who;
        try {
            var node = hierarchy.get(entitlement.getDistributorId());
            who = node.fullName() + " (" + node.businessId() + ")";
        } catch (RuntimeException missing) {
            who = "A customer";
        }
        inApp.raiseAll(
                List.copyOf(recipients.keySet()),
                Notifications.REWARD_TRACKING,
                "Receiving method chosen — " + entitlement.getTrackingNumber(),
                who + " chose " + summary + ".",
                "/reward-tracking?id=" + entitlement.getId());
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToNull(String note, String fallback) {
        String trimmed = trim(note);
        return trimmed == null ? fallback : trimmed;
    }
}
