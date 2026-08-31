package com.democode.mlmsittu.shared.notify;

/**
 * Outbound messages to people.
 *
 * <p>Email only. SMS was dropped on 28 Aug 2026 at the client's decision — no provider is being
 * chosen, so mobile OTP is out of scope rather than deferred. A {@code sendSms} sitting here
 * unimplemented and uncalled would suggest otherwise to the next person reading this.
 *
 * <p>The logging implementation is the default; {@code SmtpNotificationSender} takes over as soon
 * as {@code spring.mail.host} is set.
 *
 * <p>Sends go through the outbox (§8.3, P7-06) rather than happening inline. That matters more
 * than it looks: sending inside a transaction means a rollback still delivers the message, so
 * somebody gets a "verify your account" email for an account that was never created.
 */
public interface NotificationSender {

    void sendEmail(String toAddress, String subject, String body);
}
