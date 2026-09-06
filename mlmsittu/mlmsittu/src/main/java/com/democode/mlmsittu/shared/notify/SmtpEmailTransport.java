package com.democode.mlmsittu.shared.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends mail over SMTP, when there is an SMTP server to send it to.
 *
 * <p>Registered only if {@code spring.mail.host} is a non-blank value, and marked {@link Primary}
 * so it displaces {@link LoggingEmailTransport} when it is. That is the whole switch: fill in the
 * mail properties and restart, and messages start reaching real inboxes with no code change.
 * Leave them blank and everything keeps working against the log, which is what a machine with no
 * mail server wants.
 *
 * <p><b>Blank counts as absent, and that distinction is load-bearing.</b> Docker Compose passes an
 * empty string for any variable the {@code .env} does not define, so a deployment that simply has
 * not configured mail yet still hands this application {@code spring.mail.host=""}. Under
 * {@code @ConditionalOnProperty} that reads as configured: this bean activates, Boot autoconfigures
 * a mailer aimed at nothing, and every notification fails on a server whose only mistake was not
 * having set mail up yet.
 *
 * <p><b>Failures are not swallowed.</b> A send that throws propagates, so "the order was sent"
 * cannot be recorded for an order that got nowhere. Phase 7's outbox (§8.3) will change this by
 * moving the send outside the transaction — until then, failing loudly is the honest behaviour.
 */
@Component
@Primary
// Not @ConditionalOnProperty. That treats an EMPTY value as present, and compose passes an empty
// string for any variable the .env does not define — so a server with no mail configured would
// activate this bean and try to deliver every message to a host of "". The expression asks the
// question actually meant: is there a host worth connecting to.
@ConditionalOnExpression("'${spring.mail.host:}'.trim() != ''")
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
