package com.democode.mlmsittu.commerce.internal.web.dto;

import com.democode.mlmsittu.commerce.internal.domain.Customer;
import com.democode.mlmsittu.commerce.internal.domain.Invoice;
import com.democode.mlmsittu.commerce.internal.domain.Payment;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrder;
import com.democode.mlmsittu.commerce.internal.domain.SalesOrderLine;
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

/**
 * Request and response shapes for Phase 5.
 *
 * <p>Named records rather than maps, so springdoc can describe them and the frontend's generated
 * types cover them. An untyped response quietly opts that endpoint out of the drift check.
 */
public final class SalesDtos {

    private SalesDtos() {}

    // ------------------------------------------------------------------ customer

    public record CustomerRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 64) String code,
            @NotBlank(message = "REQUIRED") @Size(max = 255) String name,
            @Email(message = "INVALID_EMAIL") @Size(max = 320) String email,
            @Size(max = 32) String phone,
            @Size(max = 500) String address,
            @Size(max = 120) String city,
            UUID distributorId,
            @Size(max = 1000) String note) {}

    /** Update leaves the code alone — it is how the customer is referred to elsewhere. */
    public record UpdateCustomerRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 255) String name,
            @Email(message = "INVALID_EMAIL") @Size(max = 320) String email,
            @Size(max = 32) String phone,
            @Size(max = 500) String address,
            @Size(max = 120) String city,
            UUID distributorId,
            @Size(max = 1000) String note) {}

    public record CustomerResponse(
            UUID id,
            String code,
            String name,
            String email,
            String phone,
            String address,
            String city,
            UUID distributorId,
            String note,
            boolean active,
            Instant createdAt) {

        public static CustomerResponse from(Customer customer) {
            return new CustomerResponse(
                    customer.getId(),
                    customer.getCode(),
                    customer.getName(),
                    customer.getEmail(),
                    customer.getPhone(),
                    customer.getAddress(),
                    customer.getCity(),
                    customer.getDistributorId(),
                    customer.getNote(),
                    customer.isActive(),
                    customer.getCreatedAt());
        }
    }

    // ------------------------------------------------------------------ sales order

    public record SalesOrderLineRequest(
            UUID itemId,
            UUID setId,
            @Min(value = 1, message = "MUST_BE_AT_LEAST_ONE") int quantity,
            /** Null takes the catalogue price. A value here is a negotiated override. */
            @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE") BigDecimal unitPrice) {}

    public record CreateSalesOrderRequest(
            @NotNull(message = "REQUIRED") UUID customerId,
            UUID locationId,
            @NotEmpty(message = "AT_LEAST_ONE_LINE_REQUIRED") @Valid
                    List<SalesOrderLineRequest> lines,
            @DecimalMin(value = "0.00", message = "MUST_NOT_BE_NEGATIVE") BigDecimal discount,
            @Size(max = 500) String note) {}

    public record SalesOrderLineResponse(
            UUID id,
            int lineNo,
            UUID itemId,
            UUID setId,
            String description,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {

        static SalesOrderLineResponse from(SalesOrderLine line) {
            return new SalesOrderLineResponse(
                    line.getId(),
                    line.getLineNo(),
                    line.getItemId(),
                    line.getSetId(),
                    line.getDescription(),
                    line.getQuantity(),
                    line.getUnitPrice(),
                    line.getLineTotal());
        }
    }

    public record SalesOrderResponse(
            UUID id,
            String orderNumber,
            UUID customerId,
            UUID locationId,
            /** Named orderStatus for the same reason the error bodies do — see the DTO comment. */
            String orderStatus,
            UUID reservationId,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal total,
            String note,
            Instant createdAt,
            Instant paidAt,
            Instant fulfilledAt,
            Instant cancelledAt,
            List<SalesOrderLineResponse> lines) {

        public static SalesOrderResponse from(SalesOrder order) {
            return new SalesOrderResponse(
                    order.getId(),
                    order.getOrderNumber(),
                    order.getCustomerId(),
                    order.getLocationId(),
                    order.getStatus(),
                    order.getReservationId(),
                    order.getSubtotal(),
                    order.getDiscount(),
                    order.getTotal(),
                    order.getNote(),
                    order.getCreatedAt(),
                    order.getPaidAt(),
                    order.getFulfilledAt(),
                    order.getCancelledAt(),
                    order.getLines().stream().map(SalesOrderLineResponse::from).toList());
        }
    }

    // ------------------------------------------------------------------ payment

    public record RecordPaymentRequest(
            @NotNull(message = "REQUIRED") UUID salesOrderId,
            @NotNull(message = "REQUIRED")
                    @DecimalMin(value = "0.01", message = "MUST_BE_POSITIVE")
                    BigDecimal amount,
            @Size(max = 128) String bankRef,
            LocalDate paidOn,
            @NotNull(message = "REQUIRED") UUID slipDocumentId) {}

    public record RejectPaymentRequest(
            @NotBlank(message = "REQUIRED") @Size(max = 500) String reason) {}

    public record PaymentResponse(
            UUID id,
            UUID salesOrderId,
            BigDecimal amount,
            String bankRef,
            LocalDate paidOn,
            UUID slipDocumentId,
            String paymentStatus,
            UUID recordedBy,
            Instant recordedAt,
            UUID verifiedBy,
            Instant verifiedAt,
            String rejectionReason) {

        public static PaymentResponse from(Payment payment) {
            return new PaymentResponse(
                    payment.getId(),
                    payment.getSalesOrderId(),
                    payment.getAmount(),
                    payment.getBankRef(),
                    payment.getPaidOn(),
                    payment.getSlipDocumentId(),
                    payment.getStatus(),
                    payment.getRecordedBy(),
                    payment.getRecordedAt(),
                    payment.getVerifiedBy(),
                    payment.getVerifiedAt(),
                    payment.getRejectionReason());
        }
    }

    // ------------------------------------------------------------------ invoice

    public record InvoiceResponse(
            UUID id,
            String invoiceNumber,
            long sequenceNo,
            UUID salesOrderId,
            UUID customerId,
            BigDecimal total,
            Instant issuedAt) {

        public static InvoiceResponse from(Invoice invoice) {
            return new InvoiceResponse(
                    invoice.getId(),
                    invoice.getInvoiceNumber(),
                    invoice.getSequenceNo(),
                    invoice.getSalesOrderId(),
                    invoice.getCustomerId(),
                    invoice.getTotal(),
                    invoice.getIssuedAt());
        }
    }

    public record FulfilmentResponse(SalesOrderResponse order, InvoiceResponse invoice) {}

    public record SlipAccessResponse(String token, String url, long expiresInSeconds) {}
}
