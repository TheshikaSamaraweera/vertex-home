package com.democode.mlmsittu.inventory.api;

import java.util.UUID;

/**
 * A read of one item's position at one location.
 *
 * <p>{@code available} is derived, never stored: reservation increments {@code reserved} and
 * leaves {@code onHand} alone until the goods physically leave (architecture §4.5).
 */
public record StockView(UUID itemId, UUID locationId, int onHand, int reserved) {

    public int available() {
        return onHand - reserved;
    }
}
