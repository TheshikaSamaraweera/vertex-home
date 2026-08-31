package com.democode.mlmsittu.shared.ratelimit.internal;

import com.democode.mlmsittu.shared.ratelimit.api.RateLimiter;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Single-instance sliding-window limiter (development plan P1-07).
 *
 * <p><b>Superseded by {@link JdbcRateLimiter} (P7-05), which is {@code @Primary}.</b> Kept rather
 * than deleted because it is the right implementation for a single instance with no database —
 * and because it documents, by contrast, exactly what the shared one buys: limits that hold
 * across instances and survive a restart.
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

    /** Above this many tracked keys, sweep expired entries before accepting a new one. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.nanoTime();
        long windowNanos = window.toNanos();

        if (attempts.size() > SWEEP_THRESHOLD) {
            sweep(now, windowNanos);
        }

        Deque<Long> timestamps = attempts.computeIfAbsent(key, unused -> new ArrayDeque<>());
        synchronized (timestamps) {
            discardExpired(timestamps, now, windowNanos);
            if (timestamps.size() >= limit) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    @Override
    public void reset(String key) {
        attempts.remove(key);
    }

    private void discardExpired(Deque<Long> timestamps, long now, long windowNanos) {
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowNanos) {
            timestamps.pollFirst();
        }
    }

    private void sweep(long now, long windowNanos) {
        attempts.forEach(
                (key, timestamps) -> {
                    synchronized (timestamps) {
                        discardExpired(timestamps, now, windowNanos);
                        if (timestamps.isEmpty()) {
                            attempts.remove(key, timestamps);
                        }
                    }
                });
    }
}
