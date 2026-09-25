package com.democode.mlmsittu.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.identity.internal.service.AuthService;
import com.democode.mlmsittu.identity.internal.web.dto.LoginResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Many people signing in at the same moment — a morning rush.
 *
 * <p>Written against a real failure found under load: sign-in held a database connection for the
 * whole of its transaction, and the rate limiter and audit log each took a second one in a
 * transaction of their own. Once as many sign-ins arrived together as the pool had connections,
 * every connection was held by a sign-in waiting for a second — nothing could proceed until the
 * pool timed out, about 30 seconds later, and every other request in the app waited with them.
 *
 * <p>So the burst here is deliberately <b>twice the pool size</b>: it has to be large enough that
 * the old behaviour cannot get through, and it asserts both that everybody signed in and that it
 * happened in seconds rather than at the pool timeout.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Sign-in burst")
class LoginBurstTest {

    private static final String PASSWORD = "burst-password-123";

    @Autowired private AuthService auth;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int poolSize;

    @Test
    @DisplayName("twice as many simultaneous sign-ins as database connections all succeed quickly")
    void burstDoesNotStarveThePool() throws Exception {
        int burst = poolSize * 2;

        // One hash shared by every fixture account: the test is about signing in, and hashing
        // twenty passwords one after another would only make it slower.
        String hash = passwordEncoder.encode(PASSWORD);
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < burst; i++) {
            emails.add(newUser(hash));
        }

        ExecutorService pool = Executors.newFixedThreadPool(burst);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LoginResponse>> results = new ArrayList<>();
        try {
            for (int i = 0; i < burst; i++) {
                String email = emails.get(i);
                // A different address per person, as in real life: one office network's limit
                // is not what is being tested here.
                String ip = "10.20.0." + (i + 1);
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    MockHttpServletRequest request = new MockHttpServletRequest();
                                    request.setRemoteAddr(ip);
                                    return auth.login(
                                            email, PASSWORD, request, new MockHttpServletResponse());
                                }));
            }

            long began = System.nanoTime();
            start.countDown();

            for (Future<LoginResponse> result : results) {
                // Generous per sign-in, far below the pool's 30-second timeout that the old code
                // ended at.
                LoginResponse response = result.get(20, TimeUnit.SECONDS);
                assertThat(response.mfaRequired()).isFalse();
            }

            Duration took = Duration.ofNanos(System.nanoTime() - began);
            assertThat(took)
                    .as("%d sign-ins at once should take seconds, not the pool timeout", burst)
                    .isLessThan(Duration.ofSeconds(20));
        } finally {
            pool.shutdownNow();
        }
    }

    private String newUser(String passwordHash) {
        AppUser user = new AppUser();
        String email = "burst-" + UUID.randomUUID() + "@test.local";
        user.setEmail(email);
        user.setFullName("Burst fixture");
        user.setPasswordHash(passwordHash);
        user.setStatusValue(UserStatus.ACTIVE);
        users.saveAndFlush(user);
        return email;
    }
}
