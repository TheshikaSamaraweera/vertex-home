package com.democode.mlmsittu.commerce.internal.web.dto;

import com.democode.mlmsittu.commerce.internal.domain.GoodsReceipt;
import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrder;
import com.democode.mlmsittu.commerce.internal.domain.Supplier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ProcurementDtos {

    private ProcurementDtos() {}

    // ------------------------------------------------------------------ supplier

    /**
     * @param email the company's address; {@code contactEmail} belongs to the named person
     * @param address shown on the screen as "Location"
     */
    public record SupplierRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 64) String code,
            @NotBlank(message = "REQUIRED") @Size(max = 255) String name,
            @Size(max = 255) String contactName,
            @Email(message = "INVALID_EMAIL") @Size(max = 320) String email,
            @Size(max = 32) String phone,
            @Size(max = 500) String address,
            @Email(message = "INVALID_EMAIL") @Size(max = 320) String contactEmail,
            @Size(max = 32) String contactPhone) {}

    public record SupplierResponse(
            UUID id,
            String code,
            String name,
            String contactName,
            String email,
            String phone,
            String address,
            String contactEmail,
            String contactPhone,
            boolean active) {

        public static SupplierResponse from(Supplier supplier) {
            return new SupplierResponse(
                    supplier.getId(),
                    supplier.getCode(),
                    supplier.getName(),
                    supplier.getContactName(),
                    supplier.getEmail(),
                    supplier.getPhone(),
                    supplier.getAddress(),
                    supplier.getContactEmail(),
                    supplier.getContactPhone(),
                    supplier.isActive());
        }
    }

    // ------------------------------------------------------------------ supplier prices

    public record SupplierPriceRequest(
            @NotNull(message = "REQUIRED") UUID supplierId,
            @NotNull(message = "REQUIRED")
                    @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE")
                    BigDecimal price,
            @Size(max = 255) String note) {}

    public record SupplierPriceResponse(
            UUID id, UUID itemId, UUID supplierId, BigDecimal price, String note) {

        public static SupplierPriceResponse from(
                com.democode.mlmsittu.commerce.internal.domain.ItemSupplierPrice row) {
            return new SupplierPriceResponse(
                    row.getId(), row.getItemId(), row.getSupplierId(), row.getPrice(), row.getNote());
        }
    }

    // ------------------------------------------------------------------ purchase order

    public record PurchaseOrderLineRequest(
            @NotNull(message = "REQUIRED") UUID itemId,
            @Min(value = 1, message = "MUST_BE_AT_LEAST_ONE") int quantity,
            @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE") BigDecimal unitCost) {}

    public record CreatePurchaseOrderRequest(
            @NotNull(message = "REQUIRED") UUID supplierId,
            UUID locationId,
            LocalDate expectedDate,
            @Size(max = 500) String note,
            @NotEmpty(message = "AT_LEAST_ONE_LINE_REQUIRED") @Valid
                    List<PurchaseOrderLineRequest> lines) {}

    public record ReplaceLinesRequest(
            @NotEmpty(message = "AT_LEAST_ONE_LINE_REQUIRED") @Valid
                    List<PurchaseOrderLineRequest> lines) {}

    public record PurchaseOrderLineResponse(
            UUID id,
            UUID itemId,
            int lineNo,
            int quantityOrdered,
            int quantityReceived,
            int outstanding,
            boolean closed,
            BigDecimal unitCost,
            BigDecimal lineTotal) {}

    /**
     * @param sentToEmail where the order document went; null until it is sent
     * @param arrivalAttestedName who signed for the delivery; null until somebody has
     */
    public record PurchaseOrderResponse(
            UUID id,
            String poNumber,
            UUID supplierId,
            UUID locationId,
            String status,
            LocalDate expectedDate,
            String note,
            BigDecimal total,
            Instant createdAt,
            Instant sentAt,
            Instant closedAt,
            String sentToEmail,
            Instant arrivedAt,
            String arrivalAttestedName,
            List<PurchaseOrderLineResponse> lines) {

        public static PurchaseOrderResponse from(PurchaseOrder order) {
            return new PurchaseOrderResponse(
                    order.getId(),
                    order.getPoNumber(),
                    order.getSupplierId(),
                    order.getLocationId(),
                    order.getStatus(),
                    order.getExpectedDate(),
                    order.getNote(),
                    order.total(),
                    order.getCreatedAt(),
                    order.getSentAt(),
                    order.getClosedAt(),
                    order.getSentToEmail(),
                    order.getArrivedAt(),
                    order.getArrivalAttestedName(),
                    order.getLines().stream()
                            .map(
                                    line ->
                                            new PurchaseOrderLineResponse(
                                                    line.getId(),
                                                    line.getItemId(),
                                                    line.getLineNo(),
                                                    line.getQuantityOrdered(),
                                                    line.getQuantityReceived(),
                                                    line.outstanding(),
                                                    line.isClosed(),
                                                    line.getUnitCost(),
                                                    line.lineTotal()))
                            .toList());
        }
    }

    // ------------------------------------------------------------------ goods receipt

    /** Typing your own name to confirm a delivery arrived. */
    public record ConfirmArrivalRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 255) String attestedName) {}

    /** @param locationId the store for this line; null means the receipt store */
    public record GoodsReceiptLineRequest(
            @NotNull(message = "REQUIRED") UUID purchaseOrderLineId,
            @Min(value = 1, message = "MUST_BE_AT_LEAST_ONE") int quantity,
            UUID locationId) {}

    /** @param locationId the store for lines that do not name their own; null means the order one */
    public record CreateGoodsReceiptRequest(
            @NotNull(message = "REQUIRED") UUID purchaseOrderId,
            UUID locationId,
            @Size(max = 500) String supplierNote,
            @NotEmpty(message = "AT_LEAST_ONE_LINE_REQUIRED") @Valid
                    List<GoodsReceiptLineRequest> lines) {}

    /** @param locationId the store for this line; null means the receipt store */
    public record ManualGoodsReceiptLineRequest(
            @NotNull(message = "REQUIRED") UUID itemId,
            @Min(value = 1, message = "MUST_BE_AT_LEAST_ONE") int quantity,
            UUID locationId) {}

    /**
     * Goods with no purchase order behind them.
     *
     * <p>Supplier and store are both required. Stock that appears from nowhere, in no particular
     * place, is what the ledger exists to make impossible.
     */
    public record ManualGoodsReceiptRequest(
            @NotNull(message = "REQUIRED") UUID supplierId,
            @NotNull(message = "REQUIRED") UUID locationId,
            @Size(max = 500) String supplierNote,
            @NotEmpty(message = "AT_LEAST_ONE_LINE_REQUIRED") @Valid
                    List<ManualGoodsReceiptLineRequest> lines) {}

    /**
     * @param purchaseOrderId null on a manual entry
     * @param source {@code purchase_order} or {@code manual}
     * @param locationId the receipt default; each line carries the store it actually went into
     */
    public record GoodsReceiptResponse(
            UUID id,
            String receiptNumber,
            UUID purchaseOrderId,
            UUID supplierId,
            String source,
            UUID locationId,
            String supplierNote,
            Instant receivedAt,
            List<GoodsReceiptLineResponse> lines) {

        public static GoodsReceiptResponse from(GoodsReceipt receipt) {
            return new GoodsReceiptResponse(
                    receipt.getId(),
                    receipt.getReceiptNumber(),
                    receipt.getPurchaseOrderId(),
                    receipt.getSupplierId(),
                    receipt.getSource(),
                    receipt.getLocationId(),
                    receipt.getSupplierNote(),
                    receipt.getReceivedAt(),
                    receipt.getLines().stream()
                            .map(
                                    line ->
                                            new GoodsReceiptLineResponse(
                                                    line.getId(),
                                                    line.getPurchaseOrderLineId(),
                                                    line.getItemId(),
                                                    line.getLocationId(),
                                                    line.getQuantityReceived()))
                            .toList());
        }
    }

    public record GoodsReceiptLineResponse(
            UUID id,
            UUID purchaseOrderLineId,
            UUID itemId,
            UUID locationId,
            int quantityReceived) {}
}
