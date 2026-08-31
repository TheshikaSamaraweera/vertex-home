package com.democode.mlmsittu.commerce.internal.service;

import com.democode.mlmsittu.commerce.internal.domain.Supplier;
import com.democode.mlmsittu.commerce.internal.repo.ItemSupplierPriceRepository;
import com.democode.mlmsittu.commerce.internal.repo.PurchaseOrderRepository;
import com.democode.mlmsittu.commerce.internal.repo.SupplierRepository;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Supplier records (development plan P2-06). */
@Service
public class SupplierService {

    private final SupplierRepository suppliers;
    private final PurchaseOrderRepository purchaseOrders;
    private final ItemSupplierPriceRepository supplierPrices;

    public SupplierService(
            SupplierRepository suppliers,
            PurchaseOrderRepository purchaseOrders,
            ItemSupplierPriceRepository supplierPrices) {
        this.suppliers = suppliers;
        this.purchaseOrders = purchaseOrders;
        this.supplierPrices = supplierPrices;
    }

    /**
     * Everything the suppliers screen holds.
     *
     * <p>Grouped into a record because the list had reached six strings of the same type, four of
     * which are contact details — the shape of argument list where swapping two compiles cleanly
     * and puts the buyer's phone number in the accounts field.
     *
     * @param email the company's address; {@code contactEmail} is the named person's own
     */
    public record SupplierDetails(
            String name,
            String contactName,
            String email,
            String phone,
            String address,
            String contactEmail,
            String contactPhone) {}

    @Transactional
    @Audited(action = "SUPPLIER_CREATED", entityType = "supplier", auditFailures = true)
    public Supplier create(String code, SupplierDetails details) {

        Supplier supplier = new Supplier();
        supplier.setCode(code.trim().toUpperCase(Locale.ROOT));
        apply(supplier, details);

        try {
            Supplier saved = suppliers.saveAndFlush(supplier);
            AuditContext.record(saved.getId(), null, snapshot(saved));
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.ifConstraintIs(
                    e,
                    "supplier_code_key",
                    "DUPLICATE_SUPPLIER_CODE",
                    "A supplier with that code already exists.");
        }
    }

    @Transactional
    @Audited(action = "SUPPLIER_UPDATED", entityType = "supplier", auditFailures = true)
    public Supplier update(UUID id, SupplierDetails details) {
        Supplier supplier = require(id);
        Map<String, Object> before = snapshot(supplier);

        apply(supplier, details);

        Supplier saved = suppliers.save(supplier);
        AuditContext.record(id, before, snapshot(saved));
        return saved;
    }

    /**
     * Removes a supplier outright.
     *
     * <p>Only possible while nothing points at them. A supplier you have ordered from is referenced
     * by every one of those orders, and deleting the row would either orphan them or cascade away
     * purchasing history the business is required to keep — so that case is refused, and
     * deactivating is offered instead. A supplier added by mistake this morning deletes cleanly,
     * which is the case the button actually exists for.
     */
    @Transactional
    @Audited(action = "SUPPLIER_DELETED", entityType = "supplier", auditFailures = true)
    public void delete(UUID id) {
        Supplier supplier = require(id);

        long orders = purchaseOrders.countBySupplierId(id);
        long prices = supplierPrices.countBySupplierId(id);

        if (orders > 0 || prices > 0) {
            ConflictException conflict =
                    new ConflictException(
                            "SUPPLIER_IN_USE",
                            "That supplier is referenced by existing records and cannot be deleted."
                                + " Deactivate them instead — it stops new orders and leaves the"
                                + " history intact.");
            conflict.with("purchaseOrders", orders);
            conflict.with("itemPrices", prices);
            throw conflict;
        }

        AuditContext.record(id, snapshot(supplier), null);
        suppliers.delete(supplier);
    }

    private void apply(Supplier supplier, SupplierDetails details) {
        supplier.setName(details.name().trim());
        supplier.setContactName(blankToNull(details.contactName()));
        supplier.setEmail(blankToNull(details.email()));
        supplier.setPhone(blankToNull(details.phone()));
        supplier.setAddress(blankToNull(details.address()));
        supplier.setContactEmail(blankToNull(details.contactEmail()));
        supplier.setContactPhone(blankToNull(details.contactPhone()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Deactivating blocks new purchase orders and nothing else — orders already placed with this
     * supplier stay valid and can still be received against. A supplier you have stopped buying
     * from is not a supplier who never existed.
     */
    @Transactional
    @Audited(action = "SUPPLIER_ACTIVATION_CHANGED", entityType = "supplier", auditFailures = true)
    public Supplier setActive(UUID id, boolean active) {
        Supplier supplier = require(id);
        Map<String, Object> before = snapshot(supplier);
        supplier.setActive(active);
        Supplier saved = suppliers.save(supplier);
        AuditContext.record(id, before, snapshot(saved));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Supplier> list(boolean includeInactive) {
        return includeInactive ? suppliers.findAllOrdered() : suppliers.findAllActiveOrdered();
    }

    @Transactional(readOnly = true)
    public Supplier get(UUID id) {
        return require(id);
    }

    /** Used by purchase order creation. Rejects a deactivated supplier outright. */
    @Transactional(readOnly = true)
    public Supplier requireSelectable(UUID id) {
        Supplier supplier = require(id);
        if (!supplier.isActive()) {
            throw new ConflictException(
                    "SUPPLIER_INACTIVE",
                    "That supplier is deactivated and cannot be used on a new order.");
        }
        return supplier;
    }

    private Supplier require(UUID id) {
        return suppliers
                .findById(id)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "SUPPLIER_NOT_FOUND", "No supplier with that id."));
    }

    private Map<String, Object> snapshot(Supplier supplier) {
        return Map.of(
                "code", supplier.getCode(),
                "name", supplier.getName(),
                "active", supplier.isActive());
    }
}
