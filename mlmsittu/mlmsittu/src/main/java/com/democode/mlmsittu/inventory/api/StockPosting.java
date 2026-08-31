package com.democode.mlmsittu.inventory.api;

import java.util.UUID;

/**
 * One entry to append to the stock ledger.
 *
 * <p>Everything that moves stock — a goods receipt, an adjustment, a fulfilment — expresses itself
 * as one of these and hands it to {@link StockLedger}. No caller updates a stock level directly;
 * an ArchUnit rule enforces that at build time.
 *
 * @param qtyDelta signed, and never zero
 * @param referenceType the kind of document that caused this, e.g. {@code goods_receipt}
 * @param referenceId that document's id, so the movement can be traced back
 * @param reason required for {@link StockMovementType#ADJUSTMENT}, where nothing else explains it
 */
public record StockPosting(
        UUID itemId,
        UUID locationId,
        int qtyDelta,
        StockMovementType type,
        String referenceType,
        UUID referenceId,
        String reason,
        String note,
        UUID actorId) {

    public static StockPosting receipt(
            UUID itemId, UUID locationId, int quantity, UUID goodsReceiptId, UUID actorId) {
        return new StockPosting(
                itemId,
                locationId,
                quantity,
                StockMovementType.RECEIPT,
                "goods_receipt",
                goodsReceiptId,
                null,
                null,
                actorId);
    }

    public static StockPosting adjustment(
            UUID itemId,
            UUID locationId,
            int qtyDelta,
            String reason,
            String note,
            UUID actorId) {
        return new StockPosting(
                itemId,
                locationId,
                qtyDelta,
                StockMovementType.ADJUSTMENT,
                null,
                null,
                reason,
                note,
                actorId);
    }

    /**
     * @param quantity positive; the sign is applied here so no caller can get it wrong
     * @param entitlementId the reward the goods went out against, for traceability
     */
    public static StockPosting rewardIssue(
            UUID itemId,
            UUID locationId,
            int quantity,
            UUID entitlementId,
            String note,
            UUID actorId) {
        return new StockPosting(
                itemId,
                locationId,
                -Math.abs(quantity),
                StockMovementType.REWARD_ISSUE,
                "reward_entitlement",
                entitlementId,
                null,
                note,
                actorId);
    }

    public static StockPosting openingBalance(
            UUID itemId, UUID locationId, int quantity, UUID actorId) {
        return new StockPosting(
                itemId,
                locationId,
                quantity,
                StockMovementType.OPENING_BALANCE,
                null,
                null,
                "opening balance",
                null,
                actorId);
    }
}
