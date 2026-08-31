package com.democode.mlmsittu.shared.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends mail over SMTP, when there is an SMTP server to send it to.
 *
 * <p>Registered only if {@code spring.mail.host} is set, and marked {@link Primary} so it displaces
 * {@link LoggingEmailTransport} when it is — and {@link LoggingEmailTransport} steps aside when it is. That is the whole switch: fill in the mail
 * properties and restart, and purchase orders start reaching suppliers with no code change. Leave
 * them out and everything keeps working against the log, which is what local development wants.
 *
 * <p><b>Failures are not swallowed.</b> A send that throws propagates, so "the order was sent"
 * cannot be recorded for an order that got nowhere. Phase 7's outbox (§8.3) will change this by
 * moving the send outside the transaction — until then, failing loudly is the honest behaviour.
 */
@Component
@Primary
@ConditionalOnProperty(name = "spring.mail.host")
public class SmtpEmailTransport implements EmailTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailTransport.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailTransport(
            JavaMailSender mailSender,
            @Value("${notifications.mail.from:${spring.mail.username:no-reply@mlmsittu.local}}")
                    String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void deliver(String toAddress, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toAddress);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            log.info("Email sent to {} — {}", toAddress, subject);
        } catch (MailException failure) {
            // Logged with the address and subject only. The body of a purchase order is
            // commercially sensitive and does not belong in an error log.
            log.error("Email to {} failed — {}", toAddress, subject, failure);
            throw failure;
        }
    }
}
