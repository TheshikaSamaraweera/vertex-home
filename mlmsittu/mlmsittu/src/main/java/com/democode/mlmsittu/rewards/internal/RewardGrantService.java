package com.democode.mlmsittu.rewards.internal;

import com.democode.mlmsittu.catalogue.api.ItemSetCatalogue;
import com.democode.mlmsittu.identity.api.UserDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.LocationDirectory.LocationRef;
import com.democode.mlmsittu.rewards.api.RewardGrants;
import com.democode.mlmsittu.rewards.api.RewardStatus;
import com.democode.mlmsittu.rewards.internal.domain.RewardEntitlement;
import com.democode.mlmsittu.rewards.internal.repo.RewardEntitlementRepository;
import com.democode.mlmsittu.shared.notify.NotificationSender;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write half of the reward mechanic: recording that somebody has earned their pack.
 *
 * <p>Kept apart from {@link RewardAdminService} for a structural reason, not a stylistic one. This
 * bean is called from {@code hierarchy} when the fourth stage completes; the admin service depends
 * on {@code hierarchy} to describe who is waiting. One class doing both would close that loop and
 * Spring would refuse to start.
 */
@Service
public class RewardGrantService implements RewardGrants, RewardStatus {

    private static final Logger log = LoggerFactory.getLogger(RewardGrantService.class);

    private final RewardEntitlementRepository entitlements;
    private final ItemSetCatalogue itemSets;
    private final UserDirectory users;
    private final LocationDirectory locations;
    private final NotificationSender notifications;

    public RewardGrantService(
            RewardEntitlementRepository entitlements,
            ItemSetCatalogue itemSets,
            UserDirectory users,
            LocationDirectory locations,
            NotificationSender notifications) {
        this.entitlements = entitlements;
        this.itemSets = itemSets;
        this.users = users;
        this.locations = locations;
        this.notifications = notifications;
    }

    /**
     * The distributor's own view. Implemented here rather than on the admin service because that
     * one depends on {@code hierarchy}, and {@code portal} calling into it would close a loop.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<RewardSnapshot> forDistributor(UUID distributorId) {
        return entitlements
                .findByDistributorId(distributorId)
                .map(
                        entitlement -> {
                            var pack = itemSets.findById(entitlement.getItemSetId()).orElse(null);
                            String store =
                                    entitlement.getIssuedFromLocationId() == null
                                            ? null
                                            : locations
                                                    .findById(entitlement.getIssuedFromLocationId())
                                                    .map(LocationRef::name)
                                                    .orElse(null);
                            return new RewardSnapshot(
                                    entitlement.getId(),
                                    entitlement.getStatus(),
                                    entitlement.getItemSetId(),
                                    pack == null ? null : pack.code(),
                                    pack == null ? null : pack.name(),
                                    entitlement.getBecameEligibleAt(),
                                    entitlement.getIssuedAt(),
                                    store);
                        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code MANDATORY}: this must run inside the transaction that completed the stage, with
     * the distributor's row locked. Starting its own would let two concurrent referral approvals
     * both reach stage four and both try to grant.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void grantPackEligibility(UUID distributorId, UUID itemSetId) {
        if (itemSetId == null) {
            // Nothing was chosen at registration, so there is nothing to claim. Worth a line in
            // the log: it is the difference between "not eligible yet" and "eligible for nothing",
            // and only one of those is somebody's mistake to fix.
            log.info(
                    "Distributor {} completed all four stages but chose no item pack — no"
                        + " entitlement recorded",
                    distributorId);
            return;
        }

        if (entitlements.findByDistributorId(distributorId).isPresent()) {
            // Already eligible, or already issued. Stage four can be reached more than once if a
            // referral is removed and replaced, and that must not produce a second pack.
            return;
        }

        RewardEntitlement entitlement = new RewardEntitlement();
        entitlement.setDistributorId(distributorId);
        entitlement.setItemSetId(itemSetId);
        entitlement.setStatus(RewardEntitlement.ELIGIBLE);

        try {
            entitlements.saveAndFlush(entitlement);
        } catch (DataIntegrityViolationException duplicate) {
            // The unique index caught a concurrent grant the read above could not see. Losing the
            // race is the correct outcome — the other transaction created exactly the row this one
            // wanted — so this is not an error to propagate.
            log.debug("Concurrent pack grant for distributor {} — the other one won", distributorId);
            return;
        }

        notifyAdministrators(distributorId, itemSetId);
    }

    /**
     * Tells the people who can act on it.
     *
     * <p>The screen is the real notification — an administrator sees the queue whether or not an
     * email arrived. This is the nudge for somebody who is not looking at it, and it deliberately
     * fails soft: a mail server being down must not roll back somebody's earned entitlement.
     */
    private void notifyAdministrators(UUID distributorId, UUID itemSetId) {
        String packName =
                itemSets.findById(itemSetId)
                        .map(set -> set.code() + " — " + set.name())
                        .orElse(itemSetId.toString());

        // Deduplicated by id: a super admin who also holds ADMIN would otherwise be told twice.
        Map<UUID, UserDirectory.UserRef> recipients = new LinkedHashMap<>();
        users.findByRole("SUPER_ADMIN").forEach(user -> recipients.put(user.id(), user));
        users.findByRole("ADMIN").forEach(user -> recipients.put(user.id(), user));

        String body =
                """
                A distributor has completed all four referral stages and is now eligible for their
                item pack.

                Pack : %s

                Open Rewards in MLM Sittu to check availability and issue it. No stock has moved.
                """
                        .formatted(packName);

        for (UserDirectory.UserRef admin : recipients.values()) {
            try {
                notifications.sendEmail(admin.email(), "An item pack is ready to issue", body);
            } catch (RuntimeException failure) {
                log.warn("Could not notify {} about a reward entitlement", admin.email(), failure);
            }
        }

        log.info(
                "Distributor {} is eligible for pack {} — {} administrator(s) notified",
                distributorId,
                packName,
                recipients.size());
    }
}
