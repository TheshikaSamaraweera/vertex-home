package com.democode.mlmsittu.inventory.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Locations, as other modules see them. Procurement needs one on every purchase order. */
public interface LocationDirectory {

    /** @param address where the store physically is; printed on a pack's boarding pass */
    record LocationRef(
            UUID id, String code, String name, boolean isDefault, boolean active, String address) {}

    Optional<LocationRef> findById(UUID locationId);

    /** The single default location, guaranteed to exist by {@code V5__inventory.sql}. */
    LocationRef defaultLocation();

    List<LocationRef> findAllActive();
}
