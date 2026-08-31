package com.democode.mlmsittu.onboarding.internal.registration;

import com.democode.mlmsittu.onboarding.api.RegistrationDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements the published read-only view of a registration. */
@Service
public class RegistrationDirectoryService implements RegistrationDirectory {

    private final RegistrationRepository registrations;

    public RegistrationDirectoryService(RegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegistrationSnapshot> latestFor(UUID userId) {
        return registrations.findByUser(userId).stream().findFirst().map(this::toSnapshot);
    }

    private RegistrationSnapshot toSnapshot(RegistrationRepository.RegistrationRow row) {
        return new RegistrationSnapshot(
                row.id(),
                row.userId(),
                row.status(),
                row.claimedBy() != null,
                row.referrerBusinessId(),
                row.nicLast4(),
                row.rejectionReason(),
                row.rejectionNote(),
                row.submittedAt(),
                row.reviewedAt(),
                registrations.timelineOf(row.id()).stream()
                        .map(
                                event ->
                                        new TimelineEntry(
                                                event.fromStatus(),
                                                event.toStatus(),
                                                // The reviewer's note is the useful half; the
                                                // reason code is already implied by the status.
                                                event.note() != null ? event.note() : event.reason(),
                                                event.createdAt()))
                        .toList());
    }
}
