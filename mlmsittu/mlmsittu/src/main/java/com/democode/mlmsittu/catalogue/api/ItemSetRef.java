package com.democode.mlmsittu.catalogue.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * A set as other modules see it.
 *
 * @param components component item id → quantity contained in <b>one</b> set. Inventory multiplies
 *     this by the number of sets requested; the catalogue does not know or care how many are being
 *     bought.
 */
public record ItemSetRef(
        UUID id,
        String code,
        String name,
        BigDecimal setPrice,
        boolean active,
        Map<UUID, Integer> components) {}
