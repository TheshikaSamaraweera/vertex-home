package com.democode.mlmsittu.inventory.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way stock changes.
 *
 * <p>Architecture §4.1: {@code stock_movement} is the append-only source of truth and
 * {@code stock_level} is a projection that can be rebuilt from it. Keeping every write behind this
 * one interface is what makes that true in practice — an ArchUnit rule stops any other class from
 * reaching the projection at all.
 */
public interface StockLedger {

    /** Append one movement and update the projection, in one transaction. */
    void post(StockPosting posting);

    /**
     * Append several movements atomically — all of them, or none.
     *
     * <p>Rows are locked in a deterministic order regardless of the order given. Two goods
     * receipts touching the same two items in opposite sequence would otherwise deadlock under
     * load; this is the same discipline §4.5 requires of reservation, applied here because a
     * multi-line receipt has exactly the same shape.
     */
    void postAll(List<StockPosting> postings);

    /**
     * Creates an empty position for an item at a location, if one does not exist yet.
     *
     * <p>Changes no balance and writes no movement — it turns "this item has never been anywhere"
     * into "this item is here, and there are none of it". The distinction matters because
     * reservation refuses a missing row with {@code STOCK_ROW_MISSING}, which reads as a fault
     * rather than as an empty shelf. Item creation calls this so a new item is immediately usable.
     */
    void ensurePosition(UUID itemId, UUID locationId);

    /** Empty when the item has never moved at that location — which is not the same as zero. */
    Optional<StockView> levelOf(UUID itemId, UUID locationId);

    List<StockView> levelsAt(UUID locationId);

    List<StockView> allLevels();

    /** Newest first. Either filter may be null to mean "any". */
    List<StockMovementView> movementHistory(UUID itemId, UUID locationId);

    /**
     * One page of history, newest first (P7-03).
     *
     * <p>This is the list that grows without bound — a warehouse makes movements forever and
     * deletes none, because the ledger is append-only. Returning all of it was always going to
     * stop working; the only question was when.
     *
     * @param beforeId exclusive upper bound from the previous page, or null to start
     * @param limit how many rows to return; the caller should ask for one more than it needs to
     *     learn whether another page exists
     */
    List<StockMovementView> movementPage(UUID itemId, UUID locationId, Long beforeId, int limit);

    /** Every movement caused by one document, e.g. all lines of a goods receipt. */
    List<StockMovementView> movementsFor(String referenceType, UUID referenceId);

    /** What the ledger says one row's balance should be. Used to check the projection. */
    long ledgerTotalFor(UUID itemId, UUID locationId);
}
