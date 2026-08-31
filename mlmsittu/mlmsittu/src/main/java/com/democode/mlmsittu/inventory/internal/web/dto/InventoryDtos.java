package com.democode.mlmsittu.inventory.internal.web.dto;

import com.democode.mlmsittu.inventory.internal.reorder.ReorderAlert;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InventoryDtos {

    private InventoryDtos() {}

    /**
     * {@code reason} is {@code @NotBlank} so the request is rejected at the DTO boundary before
     * any transaction opens. The ledger service checks it again — this is a convenience, not the
     * control.
     */
    public record AdjustStockRequest(
            @NotNull(message = "REQUIRED") UUID itemId,
            UUID locationId,
            @NotNull(message = "REQUIRED") Integer qtyDelta,
            @NotBlank(message = "REASON_REQUIRED") @Size(max = 64) String reason,
            @Size(max = 500) String note) {}

    public record StockLevelResponse(
            UUID itemId,
            UUID locationId,
            String sku,
            String itemName,
            int onHand,
            int reserved,
            int available,
            int reorderLevel,
            boolean belowReorderLevel) {}

    public record ReorderAlertResponse(
            UUID id,
            UUID itemId,
            UUID locationId,
            String sku,
            String itemName,
            int reorderLevel,
            int onHandAtDetection,
            String status,
            Instant raisedAt,
            Instant clearedAt) {

        public static ReorderAlertResponse from(ReorderAlert alert, String sku, String itemName) {
            return new ReorderAlertResponse(
                    alert.getId(),
                    alert.getItemId(),
                    alert.getLocationId(),
                    sku,
                    itemName,
                    alert.getReorderLevel(),
                    alert.getOnHandAtDetection(),
                    alert.getStatus(),
                    alert.getRaisedAt(),
                    alert.getClearedAt());
        }
    }

    /**
     * One item, totalled across every store, with the breakdown attached.
     *
     * <p>The totals are the headline because "how much of this do we have" is a question about the
     * business, not about a shelf. The breakdown rides along in the same response so opening it
     * costs nothing — and because a total with no way to see where it sits is not much use to
     * somebody about to go and pick it.
     *
     * @param belowReorderLevel judged on the <b>total</b> available. Judging it per store made a
     *     delivery split across two stores look like a shortage in both.
     */
    public record StockByItemResponse(
            UUID itemId,
            String sku,
            String itemName,
            int onHand,
            int reserved,
            int available,
            int reorderLevel,
            boolean belowReorderLevel,
            int storeCount,
            List<StockInStoreResponse> stores) {}

    public record StockInStoreResponse(
            UUID locationId,
            String locationCode,
            String locationName,
            boolean locationActive,
            int onHand,
            int reserved,
            int available) {}

    /**
     * A store and everything currently in it.
     *
     * <p>No roll-up totals. "Units on hand" summed across unrelated items is a number with no
     * meaning — fifty bars of soap and fifty litres of floor cleaner are not a hundred of
     * anything — and the screen that briefly showed them was answering a question nobody asks.
     */
    public record StoreDetailResponse(
            UUID id,
            String code,
            String name,
            String address,
            boolean isDefault,
            boolean active,
            List<StoreItemResponse> items) {}

    public record StoreItemResponse(
            UUID itemId,
            String sku,
            String itemName,
            int onHand,
            int reserved,
            int available,
            int reorderLevel) {}

    public record LocationResponse(
            UUID id, String code, String name, String address, boolean isDefault, boolean active) {}

    public record StoreRequest(
            @jakarta.validation.constraints.NotBlank(message = "REQUIRED")
                    @jakarta.validation.constraints.Size(max = 64)
                    String code,
            @jakarta.validation.constraints.NotBlank(message = "REQUIRED")
                    @jakarta.validation.constraints.Size(max = 255)
                    String name,
            @jakarta.validation.constraints.Size(max = 500) String address) {}
}
