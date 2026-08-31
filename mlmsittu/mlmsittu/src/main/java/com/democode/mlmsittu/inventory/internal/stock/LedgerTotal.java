package com.democode.mlmsittu.inventory.internal.stock;

import java.util.UUID;

/** One row of the replay query: what the ledger says the balance should be. */
public record LedgerTotal(UUID itemId, UUID locationId, long total) {}
