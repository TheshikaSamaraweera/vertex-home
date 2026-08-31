package com.democode.mlmsittu.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.repo.ItemRepository;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.InsufficientStockException;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.inventory.api.StockLedger;
import com.democode.mlmsittu.inventory.api.StockPosting;
import com.democode.mlmsittu.inventory.api.StockView;
import com.democode.mlmsittu.inventory.internal.stock.ReservationService;
import com.democode.mlmsittu.inventory.api.ReservationRequestLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The two tests Gate 3 exists for (development plan P3-05, P3-06).
 *
 * <p>Runs against {@code mlmsitty_test}, never the development database — these deliberately
 * exhaust stock and repeat a hundred times.
 *
 * <pre>
 *   ./gradlew test --tests ReservationConcurrencyTest -Prepeat=100
 * </pre>
 *
 * <h2>Proving the deadlock test is real</h2>
 *
 * A test that passes because the bug is hard to trigger is worse than no test. To confirm this one
 * catches the real thing, delete {@code .sorted()} from {@code ReservationService#lockOrder} and
 * re-run: {@link #deadlockFreeUnderOppositeOrdering()} must fail with deadlock errors. Restore it
 * afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Reservation under concurrency")
class ReservationConcurrencyTest {

    /** Overridable with {@code -Prepeat=N}; see build.gradle. */
    private static final int REPEAT = Integer.getInteger("reservation.test.repeat", 30);

    @Autowired private ReservationService reservations;
    @Autowired private StockLedger ledger;
    @Autowired private ItemRepository items;
    @Autowired private AppUserRepository users;
    @Autowired private LocationDirectory locations;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID locationId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        locationId = locations.defaultLocation().id();
        actorId = testActor();
    }

    // ==============================================================================
    // P3-05 · Deadlock
    // ==============================================================================

    @Test
    @DisplayName("P3-05 · opposite request ordering never deadlocks")
    void deadlockFreeUnderOppositeOrdering() throws Exception {
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger otherFailures = new AtomicInteger();

        for (int round = 0; round < REPEAT; round++) {
            // Fresh items each round so an earlier round's reservations cannot influence this one.
            UUID itemA = newItemWithStock(1_000);
            UUID itemB = newItemWithStock(1_000);

            // The crux. Two callers ask for the same two items in OPPOSITE order. Without the sort
            // in lockOrder, thread one locks A then B while thread two locks B then A — the
            // textbook deadlock. With it, both go in item-id order and one simply waits.
            List<ReservationRequestLine> forward =
                    List.of(new ReservationRequestLine(itemA, null, 1), new ReservationRequestLine(itemB, null, 1));
            List<ReservationRequestLine> reverse =
                    List.of(new ReservationRequestLine(itemB, null, 1), new ReservationRequestLine(itemA, null, 1));

            int threads = 8;
            CountDownLatch startGun = new CountDownLatch(1);
            List<Callable<Void>> tasks = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                List<ReservationRequestLine> order = (i % 2 == 0) ? forward : reverse;
                tasks.add(
                        () -> {
                            startGun.await();
                            try {
                                reservations.reserve(
                                        order, locationId, "test", null, null, actorId);
                            } catch (InsufficientStockException expected) {
                                // Not what this test is about — stock is deliberately plentiful.
                            } catch (Exception e) {
                                if (isDeadlock(e)) {
                                    deadlocks.incrementAndGet();
                                } else {
                                    otherFailures.incrementAndGet();
                                }
                            }
                            return null;
                        });
            }

            runAllAtOnce(tasks, startGun);
        }

        assertThat(deadlocks.get())
                .as(
                        "deadlocks across %d rounds — if this is non-zero, lockOrder() is not"
                                + " sorting before locking",
                        REPEAT)
                .isZero();
        assertThat(otherFailures.get()).as("unexpected failures").isZero();
    }

    // ==============================================================================
    // P3-06 · Oversell
    // ==============================================================================

    @Test
    @DisplayName("P3-06 · ten threads race for the last unit, exactly one wins")
    void exactlyOneWinnerForTheLastUnit() throws Exception {
        int rounds = Math.max(REPEAT / 2, 20);

        for (int round = 0; round < rounds; round++) {
            UUID itemId = newItemWithStock(1);

            int threads = 10;
            CountDownLatch startGun = new CountDownLatch(1);
            AtomicInteger succeeded = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            AtomicInteger unexpected = new AtomicInteger();

            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(
                        () -> {
                            startGun.await();
                            try {
                                reservations.reserve(
                                        List.of(new ReservationRequestLine(itemId, null, 1)),
                                        locationId,
                                        "test",
                                        null,
                                        null,
                                        actorId);
                                succeeded.incrementAndGet();
                            } catch (InsufficientStockException expected) {
                                rejected.incrementAndGet();
                            } catch (Exception e) {
                                unexpected.incrementAndGet();
                            }
                            return null;
                        });
            }

            runAllAtOnce(tasks, startGun);

            assertThat(succeeded.get()).as("winners in round %d", round).isEqualTo(1);
            assertThat(rejected.get()).as("losers in round %d", round).isEqualTo(threads - 1);
            assertThat(unexpected.get()).as("unexpected errors in round %d", round).isZero();

            StockView view = ledger.levelOf(itemId, locationId).orElseThrow();
            assertThat(view.onHand()).as("on_hand is never decremented by reservation").isEqualTo(1);
            assertThat(view.reserved()).as("exactly one unit reserved").isEqualTo(1);
            assertThat(view.available()).as("nothing left to give").isZero();
        }
    }

    // ==============================================================================
    // P3-04 · No partial state after a failure
    // ==============================================================================

    @Test
    @DisplayName("P3-04 · a rejected reservation leaves no component touched")
    void failedReservationLeavesNoPartialState() {
        UUID plentiful = newItemWithStock(100);
        UUID scarce = newItemWithStock(1);

        // The plentiful item may or may not be locked first depending on how the UUIDs sort —
        // either way, once the scarce one fails the whole thing must roll back.
        List<ReservationRequestLine> lines =
                List.of(new ReservationRequestLine(plentiful, null, 5), new ReservationRequestLine(scarce, null, 10));

        assertThatInsufficientStock(
                () -> reservations.reserve(lines, locationId, "test", null, null, actorId));

        assertThat(ledger.levelOf(plentiful, locationId).orElseThrow().reserved())
                .as("the component that succeeded must be rolled back too")
                .isZero();
        assertThat(ledger.levelOf(scarce, locationId).orElseThrow().reserved()).isZero();
    }

    @Test
    @DisplayName("P3-07 · release restores availability on every component")
    void releaseRestoresAvailability() {
        UUID itemA = newItemWithStock(10);
        UUID itemB = newItemWithStock(10);

        var reservation =
                reservations.reserve(
                        List.of(new ReservationRequestLine(itemA, null, 4), new ReservationRequestLine(itemB, null, 6)),
                        locationId,
                        "test",
                        null,
                        null,
                        actorId);

        assertThat(ledger.levelOf(itemA, locationId).orElseThrow().reserved()).isEqualTo(4);
        assertThat(ledger.levelOf(itemB, locationId).orElseThrow().reserved()).isEqualTo(6);
        assertThat(ledger.levelOf(itemA, locationId).orElseThrow().onHand())
                .as("reservation must not decrement on_hand")
                .isEqualTo(10);

        reservations.release(reservation.id(), "test cleanup");

        assertThat(ledger.levelOf(itemA, locationId).orElseThrow().reserved()).isZero();
        assertThat(ledger.levelOf(itemB, locationId).orElseThrow().reserved()).isZero();
        assertThat(ledger.levelOf(itemA, locationId).orElseThrow().available()).isEqualTo(10);
    }

    @Test
    @DisplayName("P3-07 · a reservation cannot be released twice")
    void doubleReleaseIsRefused() {
        UUID itemId = newItemWithStock(5);
        var reservation =
                reservations.reserve(
                        List.of(new ReservationRequestLine(itemId, null, 2)),
                        locationId,
                        "test",
                        null,
                        null,
                        actorId);

        reservations.release(reservation.id(), "first");

        try {
            reservations.release(reservation.id(), "second");
            throw new AssertionError("second release should have been refused");
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage()).contains("already been closed");
        }

        assertThat(ledger.levelOf(itemId, locationId).orElseThrow().reserved())
                .as("a double release would credit the same units twice")
                .isZero();
    }

    // ==============================================================================
    // helpers
    // ==============================================================================

    private void runAllAtOnce(List<Callable<Void>> tasks, CountDownLatch startGun)
            throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<Void>> futures = new ArrayList<>();
            tasks.forEach(task -> futures.add(pool.submit(task)));

            // Every thread is parked on the latch, so they contend rather than trickle through.
            startGun.countDown();

            pool.shutdown();
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("threads did not finish — probably a real deadlock");
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        }
    }

    private static boolean isDeadlock(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null
                    && (message.contains("deadlock detected")
                            || message.contains("40P01")
                            || message.toLowerCase().contains("deadlock"))) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private void assertThatInsufficientStock(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected InsufficientStockException");
        } catch (InsufficientStockException expected) {
            assertThat(expected.getProperties()).containsKey("shortfall");
        }
    }

    private UUID newItemWithStock(int quantity) {
        Item item = new Item();
        item.setSku("CONC-" + UUID.randomUUID());
        item.setName("Concurrency fixture");
        item.setUnitCost(BigDecimal.ONE);
        item.setSellingPrice(BigDecimal.TEN);
        item.setReorderLevel(0);
        item.setActive(true);
        UUID itemId = items.saveAndFlush(item).getId();

        ledger.post(StockPosting.openingBalance(itemId, locationId, quantity, actorId));
        return itemId;
    }

    /** Stock movements need a real actor — every movement is attributable (Phase 1). */
    private UUID testActor() {
        return users.findByEmail("concurrency@test.local")
                .map(AppUser::getId)
                .orElseGet(
                        () -> {
                            AppUser user = new AppUser();
                            user.setEmail("concurrency@test.local");
                            user.setFullName("Concurrency Test Actor");
                            user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
                            user.setStatusValue(UserStatus.ACTIVE);
                            return users.saveAndFlush(user).getId();
                        });
    }
}
