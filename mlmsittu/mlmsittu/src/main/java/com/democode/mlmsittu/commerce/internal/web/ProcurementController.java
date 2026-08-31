package com.democode.mlmsittu.commerce.internal.web;

import com.democode.mlmsittu.commerce.internal.service.GoodsReceiptService;
import com.democode.mlmsittu.commerce.internal.service.PurchaseOrderService;
import com.democode.mlmsittu.commerce.internal.service.SupplierPriceService;
import com.democode.mlmsittu.commerce.internal.service.SupplierService;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.ConfirmArrivalRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.CreateGoodsReceiptRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.CreatePurchaseOrderRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.GoodsReceiptResponse;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.ManualGoodsReceiptRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.PurchaseOrderResponse;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.ReplaceLinesRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.SupplierPriceRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.SupplierPriceResponse;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.SupplierRequest;
import com.democode.mlmsittu.commerce.internal.web.dto.ProcurementDtos.SupplierResponse;
import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.shared.api.PagedResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Procurement endpoints.
 *
 * <p>Roles follow architecture §8.1, and the split is deliberate: {@code procurement_officer}
 * places orders, {@code inventory_clerk} books what physically arrived. One person doing both
 * could order goods, record a delivery that never came, and leave the books balanced.
 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('STAFF')")
public class ProcurementController {

    private final SupplierService suppliers;
    private final SupplierPriceService supplierPrices;
    private final PurchaseOrderService purchaseOrders;
    private final GoodsReceiptService goodsReceipts;
    private final CurrentUser currentUser;

    public ProcurementController(
            SupplierService suppliers,
            SupplierPriceService supplierPrices,
            PurchaseOrderService purchaseOrders,
            GoodsReceiptService goodsReceipts,
            CurrentUser currentUser) {
        this.suppliers = suppliers;
        this.supplierPrices = supplierPrices;
        this.purchaseOrders = purchaseOrders;
        this.goodsReceipts = goodsReceipts;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ supplier prices

    /**
     * What each supplier charges for an item.
     *
     * <p>Under {@code /items/{id}} because that is where somebody looks for it, but owned by
     * procurement: the catalogue module has no knowledge of suppliers, and giving it any would
     * make an item depend on who happens to sell it.
     *
     * <p>Readable by anyone signed in — buying prices are commercially sensitive but every role
     * that touches an order already sees unit cost, so hiding them here would be theatre.
     */
    @GetMapping("/items/{itemId}/supplier-prices")
    public PagedResponse<SupplierPriceResponse> supplierPrices(@PathVariable UUID itemId) {
        return PagedResponse.of(
                supplierPrices.forItem(itemId).stream().map(SupplierPriceResponse::from).toList());
    }

    /**
     * Upsert: re-quoting does not require knowing whether a price already exists.
     *
     * <p>Open to the inventory clerk as well as procurement, because the client asked for supplier
     * prices to be set on the item form and that form is the clerk's. This does not weaken the
     * §8.1 separation, which is about <b>ordering versus receiving</b> — recording what a supplier
     * charges commits nobody to anything. Placing the order and booking the delivery remain in
     * different hands.
     */
    @PutMapping("/items/{itemId}/supplier-prices")
    @PreAuthorize("hasAnyRole('PROCUREMENT_OFFICER', 'INVENTORY_CLERK')")
    public SupplierPriceResponse setSupplierPrice(
            @PathVariable UUID itemId, @Valid @RequestBody SupplierPriceRequest body) {
        return SupplierPriceResponse.from(
                supplierPrices.set(
                        itemId,
                        body.supplierId(),
                        body.price(),
                        body.note(),
                        currentUser.requireId()));
    }

    @DeleteMapping("/items/{itemId}/supplier-prices/{supplierId}")
    @PreAuthorize("hasAnyRole('PROCUREMENT_OFFICER', 'INVENTORY_CLERK')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSupplierPrice(@PathVariable UUID itemId, @PathVariable UUID supplierId) {
        supplierPrices.remove(itemId, supplierId);
    }

    // ------------------------------------------------------------------ suppliers

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PROCUREMENT_OFFICER')")
    public SupplierResponse createSupplier(@Valid @RequestBody SupplierRequest body) {
        return SupplierResponse.from(suppliers.create(body.code(), detailsOf(body)));
    }

    @GetMapping("/suppliers")
    public PagedResponse<SupplierResponse> listSuppliers(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return PagedResponse.of(
                suppliers.list(includeInactive).stream().map(SupplierResponse::from).toList());
    }

    @GetMapping("/suppliers/{id}")
    public SupplierResponse getSupplier(@PathVariable UUID id) {
        return SupplierResponse.from(suppliers.get(id));
    }

    @PutMapping("/suppliers/{id}")
    @PreAuthorize("hasRole('PROCUREMENT_OFFICER')")
    public SupplierResponse updateSupplier(
            @PathVariable UUID id, @Valid @RequestBody SupplierRequest body) {
        return SupplierResponse.from(suppliers.update(id, detailsOf(body)));
    }

    private SupplierService.SupplierDetails detailsOf(SupplierRequest body) {
        return new SupplierService.SupplierDetails(
                body.name(),
                body.contactName(),
                body.email(),
                body.phone(),
                body.address(),
                body.contactEmail(),
                body.contactPhone());
    }

    /**
     * Deletes a supplier, if nothing points at them.
     *
     * <p>A supplier you have ordered from is refused with {@code SUPPLIER_IN_USE} and the number of
     * records holding them, so the answer explains itself rather than just saying no.
     */
    @DeleteMapping("/suppliers/{id}")
    @PreAuthorize("hasRole('PROCUREMENT_OFFICER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSupplier(@PathVariable UUID id) {
        suppliers.delete(id);
    }

    @PostMapping("/suppliers/{id}/deactivate")
    @PreAuthorize("hasRole('PROCUREMENT_OFFICER')")
    public SupplierResponse deactivateSupplier(@PathVariable UUID id) {
        return SupplierResponse.from(suppliers.setActive(id, false));
    }

    @PostMapping("/suppliers/{id}/activate")
    @PreAuthorize("hasRole('PROCUREMENT_OFFICER')")
    public SupplierResponse activateSupplier(@PathVariable UUID id) {
        return SupplierResponse.from(suppliers.setActive(id, true));
    }

    // ------------------------------------------------------------------ purchase orders

    @PostMapping("/purchase-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PROCUREMENT_OFFICER')")
    public PurchaseOrderResponse createPurchaseOrder(
            @Valid @RequestBody CreatePurchaseOrderRequest body) {

        List<PurchaseOrderService.LineRequest> lines =
                body.lines().stream()
                        .map(
                                line ->
                                        new PurchaseOrderService.LineRequest(
                                                line.itemId(), line.quantity(), line.unitCost()))
                        .toList();

        return PurchaseOrderResponse.from(
                purchaseOrders.create(
                        body.supplierId(),
                        body.locationId(),
                        body.expectedDate(),
                        body.note(),
                        lines,
                        currentUser.requireId()));
    }

    @GetMapping("/purchase-orders")
    public PagedResponse<PurchaseOrderResponse> listPurchaseOrders() {
        return PagedResponse.of(
                purchaseOrders.list().stream().map(PurchaseOrderResponse::from).toList());
    }

    @GetMapping("/purchase-orders/{id}")
    public PurchaseOrderResponse getPurchaseOrder(@PathVariable UUID id) {
        return PurchaseOrderResponse.from(purchaseOrders.get(id));
    }

    /** Draft only. A sent order's lines are frozen (P2-07). */
    @PutMapping("/purchase-orders/{id}/lines")
    @PreAuthorize("hasRole('PROCUREMENT_OFFICER')")
    public PurchaseOrderResponse replaceLines(
            @PathVariable UUID id, @Valid @RequestBody ReplaceLinesRequest body) {

        List<PurchaseOrderService.LineRequest> lines =
                body.lines().stream()
                        .map(
                                line ->
                                        new PurchaseOrderService.LineRequest(
                                                line.itemId(), line.quantity(), line.unitCost()))
                        .toList();

        return PurchaseOrderResponse.from(purchaseOrders.replaceLines(id, lines));
    }

    @PostMapping("/purchase-orders/{id}/send")
    @PreAuthorize("hasRole('PROCUREMENT_OFFICER')")
    public PurchaseOrderResponse sendPurchaseOrder(@PathVariable UUID id) {
        return PurchaseOrderResponse.from(purchaseOrders.send(id));
    }

    /**
     * Confirms the delivery physically arrived, by typing your own name.
     *
     * <p>Open to every signed-in role, not just the inventory clerk. The client asked for exactly
     * that: whoever is at the door when the lorry comes signs for it, and making them find a clerk
     * first would just mean somebody signs on their behalf.
     *
     * <p>Note what this does <b>not</b> do: no stock moves. The goods are in the building, not on
     * a shelf. {@code POST /goods-receipts} is what puts them away.
     */
    @PostMapping("/purchase-orders/{id}/confirm-arrival")
    public PurchaseOrderResponse confirmArrival(
            @PathVariable UUID id, @Valid @RequestBody ConfirmArrivalRequest body) {
        return PurchaseOrderResponse.from(
                purchaseOrders.confirmArrival(id, body.attestedName(), currentUser.requireId()));
    }

    /** Arrived, or part-stored: the orders with goods still waiting for a shelf. */
    @GetMapping("/purchase-orders/awaiting-storing")
    public PagedResponse<PurchaseOrderResponse> listAwaitingStoring() {
        return PagedResponse.of(
                purchaseOrders.awaitingStoring().stream()
                        .map(PurchaseOrderResponse::from)
                        .toList());
    }

    @PostMapping("/purchase-orders/{id}/cancel")
    @PreAuthorize("hasRole('PROCUREMENT_OFFICER')")
    public PurchaseOrderResponse cancelPurchaseOrder(@PathVariable UUID id) {
        return PurchaseOrderResponse.from(purchaseOrders.cancel(id));
    }

    @GetMapping("/purchase-orders/{id}/receipts")
    public PagedResponse<GoodsReceiptResponse> listReceiptsForOrder(@PathVariable UUID id) {
        return PagedResponse.of(
                goodsReceipts.listForOrder(id).stream().map(GoodsReceiptResponse::from).toList());
    }

    // ------------------------------------------------------------------ goods receipt

    /**
     * Puts an arrived delivery into stores. This is where stock actually increases.
     *
     * <p>Refused with {@code PURCHASE_ORDER_NOT_RECEIVABLE} while the order is merely sent —
     * nobody has yet said the goods are here.
     */
    @PostMapping("/goods-receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public GoodsReceiptResponse receiveGoods(
            @Valid @RequestBody CreateGoodsReceiptRequest body) {

        List<GoodsReceiptService.ReceiptLineRequest> lines =
                body.lines().stream()
                        .map(
                                line ->
                                        new GoodsReceiptService.ReceiptLineRequest(
                                                line.purchaseOrderLineId(),
                                                line.quantity(),
                                                line.locationId()))
                        .toList();

        return GoodsReceiptResponse.from(
                goodsReceipts.storeFromOrder(
                        body.purchaseOrderId(),
                        body.locationId(),
                        lines,
                        body.supplierNote(),
                        currentUser.requireId()));
    }

    /** Goods that arrived with no purchase order behind them. */
    @PostMapping("/goods-receipts/manual")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public GoodsReceiptResponse receiveGoodsManually(
            @Valid @RequestBody ManualGoodsReceiptRequest body) {

        List<GoodsReceiptService.ManualLineRequest> lines =
                body.lines().stream()
                        .map(
                                line ->
                                        new GoodsReceiptService.ManualLineRequest(
                                                line.itemId(), line.quantity(), line.locationId()))
                        .toList();

        return GoodsReceiptResponse.from(
                goodsReceipts.storeManual(
                        body.supplierId(),
                        body.locationId(),
                        lines,
                        body.supplierNote(),
                        currentUser.requireId()));
    }

    /** Everything that has been put into a store, newest first. */
    @GetMapping("/goods-receipts")
    public PagedResponse<GoodsReceiptResponse> listGoodsReceipts() {
        return PagedResponse.of(
                goodsReceipts.list().stream().map(GoodsReceiptResponse::from).toList());
    }

    @GetMapping("/goods-receipts/{id}")
    public GoodsReceiptResponse getGoodsReceipt(@PathVariable UUID id) {
        return GoodsReceiptResponse.from(goodsReceipts.get(id));
    }
}
