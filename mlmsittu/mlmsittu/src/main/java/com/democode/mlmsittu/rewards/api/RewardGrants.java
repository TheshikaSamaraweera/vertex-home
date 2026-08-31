package com.democode.mlmsittu.rewards.api;

import java.util.UUID;

/**
 * The one thing another module may do to the reward ledger: say somebody has earned their pack.
 *
 * <p>Deliberately narrow. {@code hierarchy} knows when the four stages are complete and knows
 * nothing about stock, stores or who hands goods over; it should not learn. Everything after this
 * call — checking availability, choosing a store, moving the stock — belongs to an administrator
 * and to this module.
 *
 * <p><b>Called inside the caller's transaction, with the distributor's row already locked.</b>
 * That is load-bearing: §0.2's mechanic derives stage completion from a counted value, and
 * granting in a separate transaction would reintroduce the double-award race the lock exists to
 * prevent.
 */
public interface RewardGrants {

    /**
     * Records that a distributor has completed all four stages and may claim their pack.
     *
     * <p>Grants nothing by itself — it creates a row an administrator has to act on. No stock
     * moves here.
     *
     * <p>Idempotent, and quiet about it: a distributor who is already eligible, or has already
     * been issued their pack, is left exactly as they are. Stage four can be reached more than
     * once if a referral is removed and replaced, and that must not produce a second pack.
     *
     * @param itemSetId the pack chosen at registration; null means none was chosen, and nothing
     *     is recorded — there is no entitlement to a pack nobody picked
     */
    void grantPackEligibility(UUID distributorId, UUID itemSetId);
}
