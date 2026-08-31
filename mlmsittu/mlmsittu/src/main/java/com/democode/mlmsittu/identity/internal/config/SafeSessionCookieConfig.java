package com.democode.mlmsittu.identity.internal.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Treats the session cookie as what it is: untrusted input from the network.
 *
 * <h2>The bug this fixes</h2>
 *
 * Spring Session base64-decodes the cookie value and passes the result straight into
 * {@code WHERE SESSION_ID = ?}. A cookie that is not one of ours still decodes — base64 does not
 * care what the bytes mean — and the decoded rubbish can contain a NUL byte. PostgreSQL rejects a
 * text parameter containing {@code 0x00} outright, so the query fails with a
 * {@code DataIntegrityViolationException} rather than simply finding no row.
 *
 * <p>The consequence was severe and easy to miss: <b>every request returned 500</b>, including the
 * request for the error page, which touches the session again and throws again. The application
 * was unusable for that browser until its cookies were cleared by hand.
 *
 * <p>It was found the way these things usually are — moving sessions into the database in Phase 7
 * left a Tomcat-era cookie in a browser. {@code CC3E7A94F5BA0B27AEECDAA63FB6E089} is thirty-two
 * valid base64 characters that decode to twenty-three bytes, three of which are NUL. An upgrade is
 * only the most likely source, not the only one: a cookie from another application on the same
 * host, or one edited by hand, does exactly the same thing.
 *
 * <h2>The fix</h2>
 *
 * A cookie value that cannot be a session id is dropped before it reaches the repository, so the
 * request is simply unauthenticated — the honest answer to "here is a cookie I cannot make sense
 * of", and one every caller already handles.
 *
 * <p>This wraps the {@link DefaultCookieSerializer} Spring Boot builds from the
 * {@code server.servlet.session.cookie.*} properties rather than replacing it, so the cookie's
 * name, {@code HttpOnly}, {@code SameSite}, {@code Secure} and path all still come from one place.
 * Only reading changes; writing is delegated untouched.
 */
@Configuration
public class SafeSessionCookieConfig {

    /**
     * Marked {@link Primary} so Spring Session prefers this to the auto-configured serializer,
     * which is still created and still supplies every cookie attribute.
     *
     * <p>The delegate arrives through an {@link ObjectProvider} rather than as a plain parameter
     * because Boot only defines it when there is an embedded web server. Requiring it outright
     * broke every {@code @SpringBootTest} context in the suite, which is a good illustration of why
     * "it works when I run the app" is not the same as "it works".
     */
    @Bean
    @Primary
    public CookieSerializer safeCookieSerializer(
            ObjectProvider<DefaultCookieSerializer> configured,
            @Value("${server.servlet.session.cookie.name:MLMSESSION}") String cookieName,
            @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {

        DefaultCookieSerializer delegate =
                configured.getIfAvailable(() -> fallback(cookieName, sameSite, secure));
        return new RejectMalformedSessionIds(delegate);
    }

    /**
     * Used only where Boot built no serializer — a test context with no embedded server.
     *
     * <p>Configured from the same properties so behaviour does not quietly diverge between a test
     * and the running application, which is the entire hazard of having a fallback at all.
     */
    private static DefaultCookieSerializer fallback(
            String cookieName, String sameSite, boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(cookieName);
        serializer.setSameSite(capitalise(sameSite));
        serializer.setUseSecureCookie(secure);
        serializer.setUseHttpOnlyCookie(true);
        return serializer;
    }

    private static String capitalise(String value) {
        if (value == null || value.isBlank()) {
            return "Lax";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase();
    }

    /** Delegates everything, and filters what comes back from the browser. */
    public static class RejectMalformedSessionIds implements CookieSerializer {

        private static final Logger log = LoggerFactory.getLogger(RejectMalformedSessionIds.class);

        /**
         * Generous, because a session id's format is the id generator's business, not ours.
         *
         * <p>Spring Session's default generator produces a UUID, but insisting on one here would
         * silently log everybody out the day somebody configures a different generator. What is
         * being defended against is a value that cannot be <em>any</em> id — the check is about
         * form, deliberately not about content.
         */
        private static final int MAX_LENGTH = 256;

        private final DefaultCookieSerializer delegate;

        RejectMalformedSessionIds(DefaultCookieSerializer delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<String> readCookieValues(HttpServletRequest request) {
            List<String> values = delegate.readCookieValues(request);
            List<String> usable =
                    values.stream().filter(RejectMalformedSessionIds::couldBeAnId).toList();

            if (usable.size() < values.size()) {
                // Debug, not warn: a stale cookie after an upgrade is ordinary, and the browser
                // fixes it at the next login. Warning on every request would bury the log.
                log.debug(
                        "Ignored {} malformed session cookie value(s); treating the request as"
                            + " unauthenticated",
                        values.size() - usable.size());
            }
            return usable;
        }

        @Override
        public void writeCookieValue(CookieValue cookieValue) {
            delegate.writeCookieValue(cookieValue);
        }

        /**
         * Printable ASCII only, and not absurdly long.
         *
         * <p>A NUL is what breaks PostgreSQL, but every other control character is equally
         * meaningless in an identifier and equally certainly junk. A value that passes this and
         * still is not a real session simply finds no row — a clean 401 rather than a 500.
         */
        static boolean couldBeAnId(String value) {
            if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
                return false;
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 || c > 0x7E) {
                    return false;
                }
            }
            return true;
        }
    }
}
