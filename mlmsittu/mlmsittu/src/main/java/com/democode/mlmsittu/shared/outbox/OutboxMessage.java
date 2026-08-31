package com.democode.mlmsittu.shared.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One message waiting to be delivered, committed with the change that caused it.
 *
 * <p>See {@code V20__phase7_scale_up.sql} for why this exists rather than sending inline.
 */
@Entity
@Table(name = "outbox_message")
@Getter
@Setter
public class OutboxMessage {

    public static final String PENDING = "pending";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";

    /** Give up after this many tries and stop retrying, leaving the row for a human to see. */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "channel", nullable = false, length = 24)
    private String channel = "email";

    @Column(name = "recipient", nullable = false, length = 320)
    private String recipient;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "reference_type", length = 48)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    public void markSent() {
        this.status = SENT;
        this.dispatchedAt = Instant.now();
        this.attempts = this.attempts + 1;
        this.lastError = null;
    }

    /**
     * Backs off exponentially, and gives up rather than retrying forever.
     *
     * <p>A permanently bad address would otherwise be retried every poll for as long as the system
     * runs, and the log would fill with the same failure until nobody reads it.
     */
    public void markFailed(String reason) {
        this.attempts = this.attempts + 1;
        this.lastError = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
        if (this.attempts >= MAX_ATTEMPTS) {
            this.status = FAILED;
        } else {
            // 1, 2, 4, 8 minutes.
            this.nextAttemptAt = Instant.now().plusSeconds(60L * (1L << (this.attempts - 1)));
        }
    }
}
