package com.democode.mlmsittu.shared.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * The next batch of due messages, claimed exclusively.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes two instances safe to run together: each
     * poller takes rows the other has not locked and moves on instead of blocking. Without it,
     * two instances either serialise behind one another or — far worse, if the lock were omitted
     * entirely — both read the same row and send the same email twice.
     *
     * <p>Native, because JPA has no way to express {@code SKIP LOCKED}.
     */
    @Query(
            value =
                    """
                    SELECT * FROM outbox_message
                     WHERE status = 'pending' AND next_attempt_at <= :now
                     ORDER BY next_attempt_at
                     LIMIT :batch
                     FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<OutboxMessage> claimDue(@Param("now") Instant now, @Param("batch") int batch);

    long countByStatus(String status);
}
