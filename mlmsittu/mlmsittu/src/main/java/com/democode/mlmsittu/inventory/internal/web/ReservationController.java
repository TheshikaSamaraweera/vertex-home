package com.democode.mlmsittu.inventory.internal.web;

import com.democode.mlmsittu.identity.api.CurrentUser;
import com.democode.mlmsittu.inventory.api.ReservationRequestLine;
import com.democode.mlmsittu.inventory.api.ReservationView;
import com.democode.mlmsittu.inventory.api.SetAvailability;
import com.democode.mlmsittu.inventory.internal.location.LocationService;
import com.democode.mlmsittu.inventory.internal.service.AvailabilityService;
import com.democode.mlmsittu.inventory.internal.service.ReservationExpirySweep;
import com.democode.mlmsittu.inventory.internal.stock.ReservationService;
import com.democode.mlmsittu.shared.api.PagedResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('STAFF')")
public class ReservationController {

    private final ReservationService reservations;
    private final AvailabilityService availability;
    private final ReservationExpirySweep expirySweep;
    private final LocationService locations;
    private final CurrentUser currentUser;

    public ReservationController(
            ReservationService reservations,
            AvailabilityService availability,
            ReservationExpirySweep expirySweep,
            LocationService locations,
            CurrentUser currentUser) {
        this.reservations = reservations;
        this.availability = availability;
        this.expirySweep = expirySweep;
        this.locations = locations;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ availability

    /** Advisory. Only a reservation is authoritative — see {@link SetAvailability}. */
    @GetMapping("/item-sets/{id}/availability")
    public SetAvailability setAvailability(
            @PathVariable UUID id, @RequestParam(required = false) UUID locationId) {
        return availability.availabilityOf(id, locationId);
    }

    /** Every set at once, each carrying its own contention flag (P3-03). */
    @GetMapping("/item-sets/availability")
    public PagedResponse<SetAvailability> allSetAvailability(
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return PagedResponse.of(availability.availabilityOfAll(locationId, includeInactive));
    }

    // ------------------------------------------------------------------ reservations

    public record ReservationLineRequest(UUID itemId, UUID setId, @Min(1) int quantity) {}

    public record CreateReservationRequest(
            @NotEmpty(message = "AT_LEAST_ONE_LINE_REQUIRED") @Valid
                    List<ReservationLineRequest> lines,
            UUID locationId,
            @Size(max = 32) String referenceType,
            UUID referenceId,
            /** Null means the reservation holds until someone releases it. */
            Integer ttlMinutes) {}

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public ReservationView reserve(@Valid @RequestBody CreateReservationRequest body) {
        List<ReservationRequestLine> lines =
                body.lines().stream()
                        .map(
                                line ->
                                        new ReservationRequestLine(
                                                line.itemId(), line.setId(), line.quantity()))
                        .toList();

        UUID locationId = locations.require(body.locationId()).id();
        Duration ttl =
                body.ttlMinutes() == null ? null : Duration.ofMinutes(body.ttlMinutes());

        return reservations.reserve(
                lines,
                locationId,
                body.referenceType(),
                body.referenceId(),
                ttl,
                currentUser.requireId());
    }

    @GetMapping("/reservations")
    public PagedResponse<ReservationView> list(
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return PagedResponse.of(reservations.list(activeOnly));
    }

    @GetMapping("/reservations/{id}")
    public ReservationView get(@PathVariable UUID id) {
        return reservations.get(id);
    }

    /**
     * Manual release. A reservation held by a sales order is released through the order, not here
     * — see {@code SalesOrderService} — so that the order's status and its stock never disagree.
     */
    @PostMapping("/reservations/{id}/release")
    @PreAuthorize("hasRole('INVENTORY_CLERK')")
    public ReservationView release(
            @PathVariable UUID id, @RequestParam(required = false) String reason) {
        return reservations.release(id, reason == null ? "manual release" : reason);
    }

    /** Manual trigger for the expiry sweep, so P3-08 can be verified without waiting. */
    @PostMapping("/reservations/expire-scan")
    @PreAuthorize("hasAnyRole('INVENTORY_CLERK', 'SUPER_ADMIN')")
    public ReservationExpirySweep.SweepResult expireScan() {
        return expirySweep.sweep();
    }
}
