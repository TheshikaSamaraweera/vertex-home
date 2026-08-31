package com.democode.mlmsittu.catalogue.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What other modules are allowed to know about an item.
 *
 * <p>A snapshot, not an entity. Cross-module JPA associations are prohibited (architecture §1.3),
 * so procurement and inventory reference items by id and take a copy of the few fields they
 * genuinely need. That keeps the catalogue free to change its internals without breaking them.
 */
public record ItemRef(
        UUID id,
        String sku,
        String name,
        BigDecimal unitCost,
        BigDecimal sellingPrice,
        int reorderLevel,
        boolean active) {}
