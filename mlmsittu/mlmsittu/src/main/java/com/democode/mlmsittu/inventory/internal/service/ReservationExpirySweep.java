package com.democode.mlmsittu.inventory.internal.service;

import com.democode.mlmsittu.inventory.internal.stock.ReservationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Releases reservations whose TTL has passed (development plan P3-08).
 *
 * <p>A separate bean from {@link ReservationService} on purpose. Each reservation is expired in
 * its own transaction so one bad row cannot roll back the whole sweep — and calling
 * {@code expire()} from inside the service would bypass the Spring proxy, so it would silently
 * run without its own transaction or its audit entry.
 */
@Service
public class ReservationExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweep.class);

    private final ReservationService reservations;

    public ReservationExpirySweep(ReservationService reservations) {
        this.reservations = reservations;
    }

    public record SweepResult(int candidates, int expired, int skipped) {}

    @Scheduled(
            fixedDelayString = "${jobs.reservation-expiry.interval-ms:300000}",
            initialDelay = 90_000)
    public void scheduledSweep() {
        SweepResult result = sweep();
        if (result.expired() > 0) {
            log.info("Reservation expiry: {} released", result.expired());
        }
    }

    public SweepResult sweep() {
        List<UUID> candidates = reservations.findExpiredIds(Instant.now());
        int expired = 0;
        int skipped = 0;

        for (UUID id : candidates) {
            try {
                reservations.expire(id);
                expired++;
            } catch (RuntimeException e) {
                // Almost always a race: someone released it between the query and the lock. The
                // row is already closed, which is the outcome we wanted anyway.
                log.debug("Skipped reservation {} during expiry sweep: {}", id, e.getMessage());
                skipped++;
            }
        }

        return new SweepResult(candidates.size(), expired, skipped);
    }
}
