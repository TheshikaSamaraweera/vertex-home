package com.democode.mlmsittu.inventory.internal.reservation;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * Locks the reservation before releasing it.
     *
     * <p>Without this, a user cancelling in two tabs — or the expiry sweep racing a manual release
     * — would both read {@code status = 'active'} and both give the stock back, so the same units
     * would be credited twice and stock would inflate out of nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> lockForUpdate(@Param("id") UUID id);

    @Query("select r from Reservation r order by r.createdAt desc")
    List<Reservation> findAllOrdered();

    @Query("select r from Reservation r where r.status = 'active' order by r.createdAt desc")
    List<Reservation> findAllActive();

    /** Candidates for the expiry sweep. Ids only — each is then locked and re-checked. */
    @Query(
            """
            select r.id from Reservation r
            where r.status = 'active' and r.expiresAt is not null and r.expiresAt < :now
            order by r.expiresAt asc
            """)
    List<UUID> findExpiredIds(@Param("now") Instant now);
}
