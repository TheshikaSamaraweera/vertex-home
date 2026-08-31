package com.democode.mlmsittu.inventory.internal.location;

import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ConflictException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores — the physical places stock sits.
 *
 * <p>Read-only until receiving was split in two. Assigning a delivery to a store is meaningless
 * with one store, and {@code V5__inventory.sql} seeds exactly one, so the screens that ask "which
 * store?" needed a way to answer with something other than "Main Warehouse".
 */
@Service
public class LocationService implements LocationDirectory {

    /** @param address where the store physically is, for a driver rather than for the system */
    public record StoreDetails(String name, String address) {}

    private final LocationRepository locations;

    public LocationService(LocationRepository locations) {
        this.locations = locations;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocationRef> findById(UUID locationId) {
        return locations.findById(locationId).map(Location::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public LocationRef defaultLocation() {
        return locations
                .findByDefaultLocationTrue()
                .map(Location::toRef)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No default location. V5__inventory.sql seeds one; if it is"
                                            + " gone, something deleted it outside the"
                                            + " application."));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationRef> findAllActive() {
        return locations.findAllActiveOrdered().stream().map(Location::toRef).toList();
    }

    @Transactional(readOnly = true)
    public List<LocationRef> findAll() {
        return locations.findAllOrdered().stream().map(Location::toRef).toList();
    }

    @Transactional(readOnly = true)
    public List<Location> findAllStores() {
        return locations.findAllOrdered();
    }

    @Transactional
    @Audited(action = "STORE_CREATED", entityType = "location", auditFailures = true)
    public Location create(String code, StoreDetails details) {
        String normalised = code == null ? "" : code.trim().toUpperCase();
        locations
                .findByCode(normalised)
                .ifPresent(
                        existing -> {
                            ConflictException conflict =
                                    new ConflictException(
                                            "STORE_CODE_TAKEN", "A store already uses that code.");
                            conflict.with("code", normalised);
                            throw conflict;
                        });

        Location store = new Location();
        store.setCode(normalised);
        apply(store, details);
        // Never the default. That one is seeded and unique by partial index; promoting a store
        // here would mean demoting another, which nothing asks for and a stray click could do.
        store.setDefaultLocation(false);

        Location saved = locations.save(store);
        AuditContext.record(saved.getId(), null, snapshot(saved));
        return saved;
    }

    @Transactional
    @Audited(action = "STORE_UPDATED", entityType = "location", auditFailures = true)
    public Location update(UUID id, StoreDetails details) {
        Location store = requireEntity(id);
        Map<String, Object> before = snapshot(store);
        apply(store, details);
        Location saved = locations.save(store);
        AuditContext.record(id, before, snapshot(saved));
        return saved;
    }

    /**
     * Deactivating hides a store from the pickers and leaves its stock alone.
     *
     * <p>The default store cannot be deactivated: it is the fallback every unspecified posting
     * lands in, and an inactive fallback is a receipt that fails for no visible reason.
     */
    @Transactional
    @Audited(action = "STORE_ACTIVE_CHANGED", entityType = "location", auditFailures = true)
    public Location setActive(UUID id, boolean active) {
        Location store = requireEntity(id);
        if (!active && store.isDefaultLocation()) {
            throw new ConflictException(
                    "DEFAULT_STORE_REQUIRED", "The default store cannot be deactivated.");
        }
        Map<String, Object> before = snapshot(store);
        store.setActive(active);
        Location saved = locations.save(store);
        AuditContext.record(id, before, snapshot(saved));
        return saved;
    }

    private void apply(Location store, StoreDetails details) {
        store.setName(details.name());
        store.setAddress(blankToNull(details.address()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Location requireEntity(UUID id) {
        return locations
                .findById(id)
                .orElseThrow(
                        () -> new NotFoundException("LOCATION_NOT_FOUND", "No store with that id."));
    }

    private Map<String, Object> snapshot(Location store) {
        return Map.of(
                "code", store.getCode(),
                "name", store.getName(),
                "active", store.isActive());
    }

    /** Resolves a caller-supplied location, falling back to the default when none was given. */
    @Transactional(readOnly = true)
    public LocationRef require(UUID locationId) {
        if (locationId == null) {
            return defaultLocation();
        }
        return findById(locationId)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "LOCATION_NOT_FOUND", "No location with that id."));
    }
}
