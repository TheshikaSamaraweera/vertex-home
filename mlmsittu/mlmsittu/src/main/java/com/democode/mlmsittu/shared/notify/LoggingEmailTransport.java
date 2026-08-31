package com.democode.mlmsittu.shared.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes messages to the log instead of sending them.
 *
 * <p>The verification link is printed in full so it can be clicked during development. That is
 * only acceptable because no real address is ever behind it here — <b>a production deployment must
 * replace this bean</b>, or every verification link lands in the application log where anyone with
 * log access can use it.
 */
@Component
public class LoggingEmailTransport implements EmailTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailTransport.class);

    @Override
    public void deliver(String toAddress, String subject, String body) {
        log.info(
                """

                ┌── EMAIL (not actually sent — no SMTP provider configured) ──────────────────
                │ To      : {}
                │ Subject : {}
                ├─────────────────────────────────────────────────────────────────────────────
                {}
                └─────────────────────────────────────────────────────────────────────────────""",
                toAddress,
                subject,
                body);
    }
}
