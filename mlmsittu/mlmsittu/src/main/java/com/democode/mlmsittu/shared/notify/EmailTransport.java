package com.democode.mlmsittu.shared.notify;

/**
 * Actually delivering a message, as opposed to deciding one should be sent.
 *
 * <p>Split from {@link NotificationSender} when the outbox arrived (P7-06), and the split is the
 * whole point of the ticket. Business code calls {@code NotificationSender} and gets a row
 * committed alongside its own change; the dispatcher calls this afterwards and talks to a mail
 * server. Nothing in a transaction ever touches the network.
 *
 * <p>Implementations are chosen by configuration: {@link SmtpEmailTransport} when
 * {@code spring.mail.host} is set, {@link LoggingEmailTransport} otherwise.
 */
public interface EmailTransport {

    /**
     * @throws RuntimeException when delivery fails — the dispatcher catches it, records the reason
     *     and retries later. Swallowing it here would turn a dead mail server into silence.
     */
    void deliver(String toAddress, String subject, String body);
}
