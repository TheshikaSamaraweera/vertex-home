package com.democode.mlmsittu.shared.ratelimit.api;

import java.time.Duration;

/**
 * Sliding-window attempt counter.
 *
 * <p>Phase 1 ships an in-memory implementation, which means limits are per-instance and reset on
 * restart. Phase 7 (P7-05) swaps in a Redisson-backed implementation so limits hold across
 * instances. Callers depend on this interface only, so that swap changes no business code.
 */
public interface RateLimiter {

    /**
     * @return {@code true} if the attempt is allowed and has been counted, {@code false} if the
     *     caller has already used up {@code limit} attempts inside {@code window}.
     */
    boolean tryAcquire(String key, int limit, Duration window);

    /** Forget a key's history — called after a successful login so one typo costs nothing. */
    void reset(String key);
}
