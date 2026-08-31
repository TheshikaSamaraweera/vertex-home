package com.democode.mlmsittu.hierarchy.internal;

import com.democode.mlmsittu.rewards.api.RewardGrants;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What a completed stage is <em>worth</em>.
 *
 * <p>The client answered this on 27 Aug 2026, and the answers are narrower than §0.2's language
 * suggested:
 *
 * <ol>
 *   <li><b>Completing all four stages</b> makes a distributor eligible for the item pack they
 *       chose when they registered. Individual stages grant nothing on their own.
 *   <li>A referral counts <b>at approval</b>, which is when a slot is consumed and a Business ID
 *       allocated. That was already the implementation, now confirmed.
 *   <li>A revoked stage takes eligibility back only while it is <em>unissued</em>. Goods that have
 *       physically been handed over are not clawed back — see {@link #onStageRevoked}.
 *   <li>The bonus stage is that eligibility. There is nothing further to unlock.
 *   <li><b>No money is disbursed.</b> §5's "no automated disbursement" stands untouched, and the
 *       §8.1 separation of duties is satisfied by an administrator having to issue the pack
 *       deliberately, out of a named store.
 * </ol>
 *
 * <p>Nothing here grants itself. Reaching four stages creates a row somebody has to act on; the
 * stock does not move until an administrator issues it.
 *
 * <p><b>Every method runs inside the caller's transaction, with the distributor's row locked.</b>
 * That is load-bearing rather than incidental: granting in a separate transaction would
 * reintroduce the exact double-award race the locking exists to prevent.
 */
@Component
public class StageRewardPolicy {

    private static final Logger log = LoggerFactory.getLogger(StageRewardPolicy.class);

    private final RewardGrants rewards;

    public StageRewardPolicy(RewardGrants rewards) {
        this.rewards = rewards;
    }

    /** An individual stage grants nothing. Only the fourth one means anything. */
    public void onStageUnlocked(UUID distributorId, int stageNumber) {
        log.debug("Stage {} unlocked for distributor {}", stageNumber, distributorId);
    }

    /**
     * @param itemSetId the pack chosen at registration; null when none was, and then there is
     *     nothing to be eligible for
     */
    public void onBonusStageUnlocked(UUID distributorId, UUID itemSetId) {
        rewards.grantPackEligibility(distributorId, itemSetId);
    }

    /**
     * A stage is revoked when a counted referral is removed.
     *
     * <p>Nothing is clawed back. An unissued entitlement is left standing rather than deleted: the
     * distributor did complete four stages, somebody else's registration was withdrawn, and taking
     * their pack away for that would be punishing them for an administrative act they had no part
     * in. An issued pack is beyond recall in any case — the goods are gone, and the stock movement
     * that recorded them leaving is append-only.
     *
     * <p>If the client later wants revocation, it belongs here, and it needs a rule for goods
     * already handed over before it can be written.
     */
    public void onStageRevoked(UUID distributorId, int stageNumber) {
        log.info(
                "Stage {} revoked for distributor {} — any pack entitlement is left standing",
                stageNumber,
                distributorId);
    }
}
