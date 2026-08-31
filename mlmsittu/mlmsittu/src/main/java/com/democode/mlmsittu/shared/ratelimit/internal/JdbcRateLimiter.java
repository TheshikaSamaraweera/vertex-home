package com.democode.mlmsittu.shared.ratelimit.internal;

import com.democode.mlmsittu.shared.ratelimit.api.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sliding window shared by every instance (P7-05).
 *
 * <p>Replaces {@link InMemoryRateLimiter}, which counted per instance and forgot everything on
 * restart — so two instances behind a load balancer allowed twice the limit, and a restart handed
 * an attacker a clean slate. Gate 7 asks for six attempts against one instance and six against the
 * other to trip a limit of ten; that only works if the counter is somewhere both can see.
 *
 * <p><b>One row per attempt, not a counter.</b> A counter with a reset timestamp is cheaper and
 * quietly wrong: it turns a sliding window into a fixed one, and a fixed window lets somebody make
 * nearly twice the limit by straddling the boundary. Rows are small and the sweep is indexed.
 */
@Component
@Primary
public class JdbcRateLimiter implements RateLimiter {

    /** Sweep roughly one call in this many, rather than on a timer nobody would notice failing. */
    private static final int SWEEP_EVERY = 200;

    private final JdbcTemplate jdbc;
    private int callsSinceSweep;

    public JdbcRateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code REQUIRES_NEW}, deliberately.
     *
     * <p>A failed login rolls its transaction back, and the attempt must still be counted — a
     * limiter whose count disappears with the failure it is meant to be counting protects nothing.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(String key, int limit, Duration window) {
        maybeSweep(window);

        Instant since = Instant.now().minus(window);
        Integer used =
                jdbc.queryForObject(
                        "SELECT count(*) FROM rate_limit_attempt WHERE limit_key = ? AND attempted_at > ?",
                        Integer.class,
                        key,
                        java.sql.Timestamp.from(since));

        if (used != null && used >= limit) {
            return false;
        }

        jdbc.update("INSERT INTO rate_limit_attempt (limit_key) VALUES (?)", key);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(String key) {
        jdbc.update("DELETE FROM rate_limit_attempt WHERE limit_key = ?", key);
    }

    /**
     * Drops rows older than any window could care about.
     *
     * <p>Ten times the window rather than exactly one: windows differ per limit, and deleting a
     * row another limit was still counting would quietly raise that limit.
     */
    private void maybeSweep(Duration window) {
        if (++callsSinceSweep < SWEEP_EVERY) {
            return;
        }
        callsSinceSweep = 0;
        jdbc.update(
                "DELETE FROM rate_limit_attempt WHERE attempted_at < ?",
                java.sql.Timestamp.from(Instant.now().minus(window.multipliedBy(10))));
    }
}
