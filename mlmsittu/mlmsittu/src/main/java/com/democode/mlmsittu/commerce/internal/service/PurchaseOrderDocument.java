package com.democode.mlmsittu.commerce.internal.service;

import com.democode.mlmsittu.catalogue.api.ItemRef;
import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrder;
import com.democode.mlmsittu.commerce.internal.domain.PurchaseOrderLine;
import com.democode.mlmsittu.commerce.internal.domain.Supplier;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * The order as the supplier reads it.
 *
 * <p>Plain text, not HTML. Suppliers here read these on a phone, forward them to a warehouse, and
 * sometimes print them; plain text survives all three, and there is nothing in an order that
 * formatting would make clearer.
 *
 * <p>Item <em>names</em> as well as SKUs, deliberately. Our SKU means nothing on their side of the
 * transaction, and an order that can only be read by looking things up is an order that gets
 * filled wrong.
 */
final class PurchaseOrderDocument {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private PurchaseOrderDocument() {}

    static String subject(PurchaseOrder order) {
        return "Purchase order " + order.getPoNumber();
    }

    /**
     * @param items every item on the order, keyed by id; a missing one degrades to its id rather
     *     than failing the send, because a half-sent order is worse than an ugly one
     */
    static String body(PurchaseOrder order, Supplier supplier, Map<UUID, ItemRef> items) {
        StringBuilder text = new StringBuilder();

        text.append("Dear ").append(contactOrCompany(supplier)).append(",\n\n");
        text.append("Please supply the following.\n\n");
        text.append("Purchase order : ").append(order.getPoNumber()).append('\n');
        if (order.getExpectedDate() != null) {
            text.append("Required by    : ").append(DATE.format(order.getExpectedDate())).append('\n');
        }
        text.append('\n');

        text.append(String.format("%-14s  %-38s %8s %12s %14s%n", "Code", "Item", "Qty", "Unit", "Total"));
        text.append("-".repeat(90)).append('\n');

        for (PurchaseOrderLine line : order.getLines()) {
            ItemRef item = items.get(line.getItemId());
            text.append(
                    String.format(
                            "%-14s  %-38s %8d %12s %14s%n",
                            item != null ? item.sku() : "—",
                            item != null ? truncate(item.name()) : line.getItemId().toString(),
                            line.getQuantityOrdered(),
                            money(line.getUnitCost()),
                            money(line.lineTotal())));
        }

        text.append("-".repeat(90)).append('\n');
        text.append(String.format("%64s %14s%n", "Order total", money(order.total())));

        if (order.getNote() != null && !order.getNote().isBlank()) {
            text.append("\nNotes\n").append(order.getNote()).append('\n');
        }

        text.append("\nPlease confirm receipt of this order and the delivery date.\n");
        text.append("\nMLM Sittu\n");
        return text.toString();
    }

    private static String contactOrCompany(Supplier supplier) {
        String contact = supplier.getContactName();
        return contact != null && !contact.isBlank() ? contact : supplier.getName();
    }

    /** Keeps the column aligned. A name long enough to need this is already unusual. */
    private static String truncate(String name) {
        if (name == null) {
            return "—";
        }
        return name.length() <= 38 ? name : name.substring(0, 35) + "…";
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "—" : String.format("%,.2f", amount);
    }
}
