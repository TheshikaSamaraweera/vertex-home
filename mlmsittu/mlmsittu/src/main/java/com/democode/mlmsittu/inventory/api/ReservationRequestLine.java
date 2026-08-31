package com.democode.mlmsittu.inventory.api;

import java.util.UUID;

/**
 * One requested line. Exactly one of {@code itemId} and {@code setId} is set — a bare item, or a
 * set to be expanded into its components.
 */
public record ReservationRequestLine(UUID itemId, UUID setId, int quantity) {}
