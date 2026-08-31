package com.democode.mlmsittu.shared.outbox;

import com.democode.mlmsittu.shared.notify.NotificationSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What business code gets when it asks for a {@link NotificationSender} (P7-06).
 *
 * <p>It does not send anything. It writes a row that commits with whatever change asked for the
 * message, and {@link OutboxDispatcher} delivers it a moment later. Callers do not need to know
 * that, and deliberately do not: the signature is unchanged, so the switch from inline sending to
 * the outbox touched no business code at all.
 *
 * <p>The two failures this fixes were both real before it existed. A purchase order emailed its
 * supplier inside the transaction that marked it sent — a database failure one statement later
 * rolled the order back while the supplier kept the email, and a mail-server failure rolled back
 * an order that was otherwise perfectly good. Neither can happen now: the row and the order share
 * a fate, and the network is not involved in deciding it.
 */
@Component
public class OutboxNotificationSender implements NotificationSender {

    private final OutboxRepository outbox;

    public OutboxNotificationSender(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    /**
     * {@code REQUIRED}, not {@code MANDATORY}.
     *
     * <p>Almost every caller is already in a transaction and should be — that is the whole point.
     * But a message enqueued outside one is still better than a message lost, so this joins an
     * existing transaction rather than refusing when there is none.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void sendEmail(String toAddress, String subject, String body) {
        OutboxMessage message = new OutboxMessage();
        message.setChannel("email");
        message.setRecipient(toAddress);
        message.setSubject(subject);
        message.setBody(body);
        outbox.save(message);
    }
}
