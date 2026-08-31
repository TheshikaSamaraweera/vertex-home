package com.democode.mlmsittu.shared.outbox;

import com.democode.mlmsittu.shared.notify.EmailTransport;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers what the outbox holds (P7-06).
 *
 * <p>Polls rather than listens, because a poll is the only design that recovers on its own. A
 * message enqueued while the dispatcher was down is picked up on the next tick with no
 * intervention; an in-process queue would have lost it.
 *
 * <p><b>Safe to run on two instances at once.</b> {@code FOR UPDATE SKIP LOCKED} means each poller
 * claims rows the other has not, so no message is ever delivered twice — which is the specific
 * thing Gate 7 asks to be demonstrated.
 *
 * <p>Architecture §10.1 eventually puts scheduled work on a dedicated instance behind a profile.
 * That is a Phase 8 concern; this is correct without it, which is why it can ship first.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outbox;
    private final EmailTransport transport;
    private final int batchSize;

    public OutboxDispatcher(
            OutboxRepository outbox,
            EmailTransport transport,
            @Value("${outbox.batch-size:25}") int batchSize) {
        this.outbox = outbox;
        this.transport = transport;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:5000}", initialDelay = 5_000)
    public void poll() {
        try {
            int sent = dispatchBatch();
            if (sent > 0) {
                log.debug("Outbox dispatched {} message(s)", sent);
            }
        } catch (RuntimeException failure) {
            // A poller that dies takes every future message with it. Log and let the next tick try.
            log.error("Outbox poll failed", failure);
        }
    }

    /**
     * One transaction per batch, holding the row locks for its duration.
     *
     * <p>Each message's outcome is recorded whether it succeeded or not, so a transport that
     * throws for one recipient does not prevent the rest of the batch being marked.
     */
    @Transactional
    public int dispatchBatch() {
        List<OutboxMessage> due = outbox.claimDue(Instant.now(), batchSize);
        int sent = 0;

        for (OutboxMessage message : due) {
            try {
                transport.deliver(message.getRecipient(), message.getSubject(), message.getBody());
                message.markSent();
                sent++;
            } catch (RuntimeException failure) {
                message.markFailed(failure.getMessage());
                if (OutboxMessage.FAILED.equals(message.getStatus())) {
                    log.error(
                            "Giving up on outbox message {} to {} after {} attempts",
                            message.getId(),
                            message.getRecipient(),
                            message.getAttempts(),
                            failure);
                } else {
                    log.warn(
                            "Outbox message {} to {} failed, retrying at {}",
                            message.getId(),
                            message.getRecipient(),
                            message.getNextAttemptAt());
                }
            }
            outbox.save(message);
        }
        return sent;
    }
}
