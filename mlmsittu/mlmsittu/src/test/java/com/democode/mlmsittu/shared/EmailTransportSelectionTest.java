package com.democode.mlmsittu.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.democode.mlmsittu.shared.notify.EmailTransport;
import com.democode.mlmsittu.shared.notify.LoggingEmailTransport;
import com.democode.mlmsittu.shared.notify.SmtpEmailTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Which {@link EmailTransport} the application ends up with, for a given {@code spring.mail.host}.
 *
 * <p>No database and no Spring Boot test context — {@link ApplicationContextRunner} builds a
 * context holding only these beans, so this asks exactly one question and answers it in
 * milliseconds.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The same mistake was made twice, in two environments, and cost an evening each time.
 *
 * <p>Docker Compose substitutes an <b>empty string</b> for any variable the {@code .env} does not
 * define. So a deployment that has simply not set mail up yet still hands the application
 * {@code spring.mail.host=""} — and {@code @ConditionalOnProperty}, which the transport used to
 * carry, counts a present-but-empty property as configured. The result was an application that
 * activated the SMTP transport, autoconfigured a mailer aimed at nothing, and reported its own
 * health as DOWN, on a server whose only mistake was not having configured mail.
 *
 * <p>The condition is now an expression asking whether the host is non-blank. That is a one-line
 * distinction which reads as a detail and is not: it decides whether a fresh deployment comes up
 * working or comes up broken, and the failure looks nothing like its cause.
 */
class EmailTransportSelectionTest {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                    .withUserConfiguration(LoggingEmailTransport.class, SmtpEmailTransport.class);

    @Test
    @DisplayName("no mail host at all: messages go to the log")
    void unsetHostUsesTheLog() {
        context.run(
                built ->
                        assertThat(built.getBean(EmailTransport.class))
                                .isInstanceOf(LoggingEmailTransport.class));
    }

    @Test
    @DisplayName("an EMPTY mail host is not a configured one")
    void blankHostUsesTheLog() {
        // The case that matters. This is what compose passes for an undefined variable, and what
        // used to activate SMTP against a host of "".
        context.withPropertyValues("spring.mail.host=")
                .run(
                        built ->
                                assertThat(built.getBean(EmailTransport.class))
                                        .isInstanceOf(LoggingEmailTransport.class));
    }

    @Test
    @DisplayName("whitespace is not a configured host either")
    void whitespaceHostUsesTheLog() {
        // Somebody edits .env and leaves a trailing space after the '='. It should behave as blank
        // rather than as a hostname made of a space.
        context.withPropertyValues("spring.mail.host=   ")
                .run(
                        built ->
                                assertThat(built.getBean(EmailTransport.class))
                                        .isInstanceOf(LoggingEmailTransport.class));
    }

    @Test
    @DisplayName("a real mail host sends over SMTP")
    void realHostUsesSmtp() {
        context.withPropertyValues("spring.mail.host=smtp.gmail.com", "spring.mail.port=587")
                .run(
                        built ->
                                assertThat(built.getBean(EmailTransport.class))
                                        .isInstanceOf(SmtpEmailTransport.class));
    }
}
