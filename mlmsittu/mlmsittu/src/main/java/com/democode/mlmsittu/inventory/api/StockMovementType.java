package com.democode.mlmsittu.inventory.api;

/** Mirrors the {@code chk_movement_type} constraint on {@code stock_movement}. */
public enum StockMovementType {

    /** Stock that existed before the system did. Used by seeding and go-live migration. */
    OPENING_BALANCE,

    /** Goods received against a purchase order. Always positive. */
    RECEIPT,

    /** Manual correction with a mandatory reason — damage, loss, stock count. Either sign. */
    ADJUSTMENT,

    /** Stock leaving on a fulfilled sales order. Always negative. Phase 5. */
    FULFILMENT,

    /** Stock coming back from a customer. Always positive. Phase 5. */
    RETURN,

    /**
     * Aligning the <em>ledger</em> with a physical count, when reality and the books disagree.
     *
     * <p>Not to be confused with projection repair. {@code StockReconciliationService} rebuilds
     * {@code stock_level} from this ledger and writes no movement at all — writing one would move
     * the very total it is checking against. This value is for the opposite case: the ledger
     * itself is wrong because something physical happened that nobody recorded.
     */
    RECONCILIATION,

    /**
     * A referral reward pack handed to a distributor. Always negative.
     *
     * <p>Distinct from {@link #ADJUSTMENT} on purpose: an adjustment says the books were wrong,
     * and these goods left the building deliberately, against an entitlement somebody earned.
     * Collapsing the two would make "how much stock went out as rewards" unanswerable.
     */
    REWARD_ISSUE
}
