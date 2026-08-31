package com.democode.mlmsittu.commerce.internal.web;

import com.democode.mlmsittu.commerce.internal.service.CustomerService;
import com.democode.mlmsittu.commerce.internal.service.FulfilmentService;
import com.democode.mlmsittu.commerce.internal.service.InvoiceService;
import com.democode.mlmsittu.commerce.internal.service.PaymentService;
import com.democode.mlmsittu.commerce.internal.service.SalesOrderService;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.CreateSalesOrderRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.CustomerRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.CustomerResponse;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.FulfilmentResponse;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.InvoiceResponse;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.PaymentResponse;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.RecordPaymentRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.RejectPaymentRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.SalesOrderResponse;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.SlipAccessResponse;
import com.democode.mlmsittu.commerce.internal.web.dto.SalesDtos.UpdateCustomerRequest;
import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.shared.api.PagedResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sales, payments and fulfilment.
 *
 * <p>Roles follow architecture §8.1, and the split runs through the whole flow:
 * {@code finance_officer} sells and records money coming in, a <em>different</em> finance officer
 * decides whether to believe the slip, and {@code inventory_clerk} releases the goods. One person
 * doing all three could invent a customer, claim they paid, and walk out with stock.
 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('STAFF')")
public class SalesController {

    private final CustomerService customers;
    private final SalesOrderService orders;
    private final PaymentService payments;
    private final FulfilmentService fulfilment;
    private final InvoiceService invoices;
    private final CurrentUser currentUser;

    public SalesController(
            CustomerService customers,
            SalesOrderService orders,
            PaymentService payments,
            FulfilmentService fulfilment,
            InvoiceService invoices,
            CurrentUser currentUser) {
        this.customers = customers;
        this.orders = orders;
        this.payments = payments;
        this.fulfilment = fulfilment;
        this.invoices = invoices;
        this.currentUser = currentUser;
    }

    // ================================================================== customers (P5-01)

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public CustomerResponse createCustomer(@Valid @RequestBody CustomerRequest body) {
        return CustomerResponse.from(
                customers.create(
                        body.code(),
                        new CustomerService.CustomerDetails(
                                body.name(),
                                body.email(),
                                body.phone(),
                                body.address(),
                                body.city(),
                                body.distributorId(),
                                body.note()),
                        currentUser.requireId()));
    }

    /** Readable by anyone signed in — {@code support_agent} exists to answer questions about them. */
    @GetMapping("/customers")
    public PagedResponse<CustomerResponse> listCustomers(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return PagedResponse.of(
                customers.list(search, includeInactive).stream()
                        .map(CustomerResponse::from)
                        .toList());
    }

    @GetMapping("/customers/{id}")
    public CustomerResponse getCustomer(@PathVariable UUID id) {
        return CustomerResponse.from(customers.get(id));
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public CustomerResponse updateCustomer(
            @PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest body) {
        return CustomerResponse.from(
                customers.update(
                        id,
                        new CustomerService.CustomerDetails(
                                body.name(),
                                body.email(),
                                body.phone(),
                                body.address(),
                                body.city(),
                                body.distributorId(),
                                body.note())));
    }

    @PostMapping("/customers/{id}/deactivate")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public CustomerResponse deactivateCustomer(@PathVariable UUID id) {
        return CustomerResponse.from(customers.setActive(id, false));
    }

    @PostMapping("/customers/{id}/activate")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public CustomerResponse activateCustomer(@PathVariable UUID id) {
        return CustomerResponse.from(customers.setActive(id, true));
    }

    @GetMapping("/customers/{id}/orders")
    public PagedResponse<SalesOrderResponse> customerOrders(@PathVariable UUID id) {
        return PagedResponse.of(
                orders.list(id, null).stream().map(SalesOrderResponse::from).toList());
    }

    // ================================================================== orders (P5-02, P5-03)

    /**
     * Creates an order and holds its stock.
     *
     * <p>The idempotency key travels as a header rather than in the body, so it describes the
     * <em>request</em> and not the order. A retry that resends the same body with the same header
     * is a retry; the same body with a new header is a genuine second order, which is a thing
     * people legitimately do.
     */
    @PostMapping("/sales-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public SalesOrderResponse createOrder(
            @Valid @RequestBody CreateSalesOrderRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        List<SalesOrderService.LineRequest> lines =
                body.lines().stream()
                        .map(
                                line ->
                                        new SalesOrderService.LineRequest(
                                                line.itemId(),
                                                line.setId(),
                                                line.quantity(),
                                                line.unitPrice()))
                        .toList();

        return SalesOrderResponse.from(
                orders.create(
                        new SalesOrderService.OrderRequest(
                                body.customerId(),
                                body.locationId(),
                                lines,
                                body.discount(),
                                body.note(),
                                idempotencyKey),
                        currentUser.requireId()));
    }

    @GetMapping("/sales-orders")
    public PagedResponse<SalesOrderResponse> listOrders(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String status) {
        return PagedResponse.of(
                orders.list(customerId, status).stream().map(SalesOrderResponse::from).toList());
    }

    @GetMapping("/sales-orders/{id}")
    public SalesOrderResponse getOrder(@PathVariable UUID id) {
        return SalesOrderResponse.from(orders.get(id));
    }

    @GetMapping("/sales-orders/{id}/payments")
    public PagedResponse<PaymentResponse> orderPayments(@PathVariable UUID id) {
        return PagedResponse.of(
                payments.forOrder(id).stream().map(PaymentResponse::from).toList());
    }

    @GetMapping("/sales-orders/{id}/invoice")
    public ResponseEntity<InvoiceResponse> orderInvoice(@PathVariable UUID id) {
        return invoices
                .findForOrder(id)
                .map(InvoiceResponse::from)
                .map(ResponseEntity::ok)
                // 204 rather than 404: the order exists, it simply has not been fulfilled yet.
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/sales-orders/{id}/cancel")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public SalesOrderResponse cancelOrder(
            @PathVariable UUID id, @RequestParam(required = false) String reason) {
        return SalesOrderResponse.from(orders.cancel(id, reason, currentUser.requireId()));
    }

    /**
     * P5-08 · releases the goods. Inventory's call, not finance's — finance decided the money was
     * real, the warehouse decides the stock physically left.
     */
    @PostMapping("/sales-orders/{id}/fulfil")
    @PreAuthorize("hasAnyRole('INVENTORY_CLERK', 'SUPER_ADMIN')")
    public FulfilmentResponse fulfilOrder(@PathVariable UUID id) {
        var result = fulfilment.fulfil(id, currentUser.requireId());
        return new FulfilmentResponse(
                SalesOrderResponse.from(result.order()), InvoiceResponse.from(result.invoice()));
    }

    // ================================================================== payments (P5-04 to P5-07)

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public PaymentResponse recordPayment(@Valid @RequestBody RecordPaymentRequest body) {
        return PaymentResponse.from(
                payments.record(
                        new PaymentService.PaymentEntry(
                                body.salesOrderId(),
                                body.amount(),
                                body.bankRef(),
                                body.paidOn(),
                                body.slipDocumentId()),
                        currentUser.requireId()));
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN', 'SUPPORT_AGENT')")
    public PagedResponse<PaymentResponse> listPayments(
            @RequestParam(required = false) String status) {
        return PagedResponse.of(
                payments.list(status).stream().map(PaymentResponse::from).toList());
    }

    @GetMapping("/payments/{id}")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN', 'SUPPORT_AGENT')")
    public PaymentResponse getPayment(@PathVariable UUID id) {
        return PaymentResponse.from(payments.get(id));
    }

    /**
     * Authorises a look at the slip image. Logged before any bytes move, like every other private
     * document — a finance officer reading somebody's bank details is an access worth accounting
     * for.
     */
    @PostMapping("/payments/{id}/slip-access")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public SlipAccessResponse slipAccess(@PathVariable UUID id, HttpServletRequest request) {
        String token =
                payments.issueSlipAccess(id, currentUser.requireId(), request.getRemoteAddr());
        return new SlipAccessResponse(
                token, "/api/v1/documents/" + token, payments.slipTokenTtlSeconds());
    }

    @PostMapping("/payments/{id}/verify")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public PaymentResponse verifyPayment(@PathVariable UUID id) {
        return PaymentResponse.from(payments.verify(id, currentUser.requireId()));
    }

    @PostMapping("/payments/{id}/reject")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN')")
    public PaymentResponse rejectPayment(
            @PathVariable UUID id, @Valid @RequestBody RejectPaymentRequest body) {
        return PaymentResponse.from(payments.reject(id, currentUser.requireId(), body.reason()));
    }

    // ================================================================== invoices (P5-10)

    @GetMapping("/invoices")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN', 'SUPPORT_AGENT')")
    public PagedResponse<InvoiceResponse> listInvoices() {
        return PagedResponse.of(invoices.list().stream().map(InvoiceResponse::from).toList());
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAnyRole('FINANCE_OFFICER', 'SUPER_ADMIN', 'SUPPORT_AGENT')")
    public InvoiceResponse getInvoice(@PathVariable UUID id) {
        return InvoiceResponse.from(invoices.get(id));
    }
}
