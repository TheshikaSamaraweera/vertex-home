package com.democode.mlmsittu.inventory.internal.stock;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link StockLevel}. A plain class rather than a record because JPA requires a
 * no-arg constructor on an {@code @IdClass}.
 *
 * <p>Also the natural sort key for lock ordering — see {@link StockLedgerService}.
 */
public class StockLevelId implements Serializable, Comparable<StockLevelId> {

    private UUID itemId;
    private UUID locationId;

    protected StockLevelId() {}

    public StockLevelId(UUID itemId, UUID locationId) {
        this.itemId = itemId;
        this.locationId = locationId;
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    @Override
    public int compareTo(StockLevelId other) {
        int byItem = itemId.compareTo(other.itemId);
        return byItem != 0 ? byItem : locationId.compareTo(other.locationId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof StockLevelId that
                && Objects.equals(itemId, that.itemId)
                && Objects.equals(locationId, that.locationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, locationId);
    }

    @Override
    public String toString() {
        return itemId + "@" + locationId;
    }
}
