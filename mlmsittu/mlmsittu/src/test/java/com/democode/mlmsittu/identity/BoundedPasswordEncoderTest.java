package com.democode.mlmsittu.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.identity.internal.config.BoundedPasswordEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The cap is the whole point: however many callers arrive, no more than the permitted number of
 * hashes may be running at once — that is what bounds the memory a sign-in rush can take.
 */
@DisplayName("Bounded password encoder")
class BoundedPasswordEncoderTest {

    @Test
    @DisplayName("never runs more hashes at once than it is allowed")
    void capsConcurrency() throws Exception {
        CountingEncoder counting = new CountingEncoder();
        PasswordEncoder bounded = new BoundedPasswordEncoder(counting, 2);

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return bounded.matches("password", "hash");
                                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(counting.peak.get()).isEqualTo(2);
        assertThat(counting.calls.get()).isEqualTo(12);
    }

    @Test
    @DisplayName("refuses a limit that would let nothing run")
    void rejectsZero() {
        assertThatThrownBy(() -> new BoundedPasswordEncoder(new CountingEncoder(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Stands in for Argon2: slow enough that callers overlap, and records how many did. */
    private static final class CountingEncoder implements PasswordEncoder {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String encode(CharSequence rawPassword) {
            work();
            return "hash";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            work();
            return true;
        }

        private void work() {
            calls.incrementAndGet();
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
        }
    }
}
