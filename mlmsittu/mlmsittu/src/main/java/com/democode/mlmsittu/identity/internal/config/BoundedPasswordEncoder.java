package com.democode.mlmsittu.identity.internal.config;

import java.util.concurrent.Semaphore;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Lets only a fixed number of password hashes run at once; the rest queue.
 *
 * <p>Argon2 is expensive on purpose — this application's parameters take 64&nbsp;MB of memory and
 * about half a second of CPU per hash, which is what makes a stolen hash slow to crack. The same
 * cost is a hazard to the server itself: twenty people signing in in the same second would ask
 * for 1.3&nbsp;GB at once, and on a small instance that is an out-of-memory crash rather than a
 * slow morning.
 *
 * <p>Queuing costs a sign-in a little latency during a rush and keeps the worst case fixed:
 * {@code permits × 64 MB} of memory and {@code permits} cores of CPU, however many people arrive.
 * The rest of the application keeps its memory and its CPU while the rush passes. The queue is
 * fair, so nobody is overtaken indefinitely; waiting holds no database connection (see
 * {@code AuthService#login}) and, on a virtual thread, no platform thread either.
 */
public final class BoundedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final Semaphore permits;

    public BoundedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be at least 1");
        }
        this.delegate = delegate;
        this.permits = new Semaphore(maxConcurrent, true);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        acquire();
        try {
            return delegate.encode(rawPassword);
        } finally {
            permits.release();
        }
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        acquire();
        try {
            return delegate.matches(rawPassword, encodedPassword);
        } finally {
            permits.release();
        }
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        // Parsing only, no hashing: nothing to queue for.
        return delegate.upgradeEncoding(encodedPassword);
    }

    private void acquire() {
        try {
            permits.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to hash a password", interrupted);
        }
    }
}
