package com.democode.mlmsittu.inventory.api;

import java.util.UUID;

/** One component item held by a reservation. */
public record ReservationLineView(UUID itemId, int quantity) {}
