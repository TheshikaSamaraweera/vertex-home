package com.democode.mlmsittu.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.catalogue.internal.domain.Item;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService;
import com.democode.mlmsittu.catalogue.internal.service.ItemProvisioningService.OpeningStock;
import com.democode.mlmsittu.catalogue.internal.service.ItemService;
import com.democode.mlmsittu.catalogue.internal.service.ItemService.ItemDetails;
import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.domain.UserStatus;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import com.democode.mlmsittu.inventory.api.LocationDirectory;
import com.democode.mlmsittu.shared.api.Cursor;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.notify.EmailTransport;
import com.democode.mlmsittu.shared.notify.NotificationSender;
import com.democode.mlmsittu.shared.outbox.OutboxDispatcher;
import com.democode.mlmsittu.shared.outbox.OutboxMessage;
import com.democode.mlmsittu.shared.outbox.OutboxRepository;
import com.democode.mlmsittu.shared.ratelimit.api.RateLimiter;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 7 — the pieces that let more than one instance run at once.
 *
 * <p>Three separate promises are checked here, and each one is a real failure mode rather than a
 * hypothetical: pagination that neither repeats nor skips a row while the table is being written
 * to, a rate limit that is not per-instance, and a message that cannot be delivered for a
 * transaction that rolled back.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Scale-up")
class ScaleUpTest {

    @Autowired private ItemProvisioningService provisioning;
    @Autowired private ItemService items;
    @Autowired private LocationDirectory locations;
    @Autowired private RateLimiter rateLimiter;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private NotificationSender notifications;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @MockitoSpyBean private EmailTransport transport;

    private UUID store;

    @BeforeEach
    void setUp() {
        store = locations.defaultLocation().id();
        Mockito.reset(transport);

        // Start from an empty queue.
        //
        // Every test in the suite now enqueues real messages — a purchase order emails its
        // supplier, a reward pack emails the administrators — and none of them dispatch. Those
        // rows accumulate, the poller claims the oldest twenty-five first, and a test's own
        // message ends up crowded out of its batch. That produced a failure reading "expected 1
        // but was 0" which had nothing to do with the outbox and everything to do with the
        // fixtures next door.
        jdbc.update("DELETE FROM outbox_message WHERE status = 'pending'");
    }

    // ==============================================================================
    // P7-03 · cursor pagination
    // ==============================================================================

    @Test
    @DisplayName("following the cursor walks every row exactly once")
    void pagingCoversEverythingWithoutRepeating() {
        String tag = unique();
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            created.add(newItem(String.format("%s %02d", tag, i)).getId());
        }

        Set<UUID> seen = walkSlice(tag, 10);

        assertThat(seen).as("no duplicates, no gaps").containsExactlyInAnyOrderElementsOf(created);
    }

    @Test
    @DisplayName("a row inserted mid-pagination cannot duplicate one already returned")
    void insertingMidWalkDoesNotDuplicate() {
        String tag = unique();
        for (int i = 0; i < 30; i++) {
            newItem(String.format("%s %02d", tag, i));
        }

        // First page, then an insert that sorts *before* everything still to come. Under offset
        // pagination this is the classic failure: every later page shifts by one, so the last row
        // of page one comes back again on page two.
        List<Item> first = items.page(true, null, 10);
        List<Item> page = first.subList(0, 10);
        Cursor after = new Cursor(page.get(9).getName(), page.get(9).getId());

        newItem(tag + " 00 inserted");

        Set<UUID> seen = new LinkedHashSet<>();
        page.forEach(item -> seen.add(item.getId()));

        int guard = 0;
        Cursor cursor = after;
        while (cursor != null && guard++ < 20) {
            List<Item> fetched = items.page(true, cursor, 10);
            List<Item> rows = fetched.size() > 10 ? fetched.subList(0, 10) : fetched;
            for (Item row : rows) {
                assertThat(seen.add(row.getId()))
                        .as("row %s came back twice", row.getName())
                        .isTrue();
            }
            cursor =
                    fetched.size() > 10
                            ? new Cursor(rows.get(9).getName(), rows.get(9).getId())
                            : null;
        }
    }

    @Test
    @DisplayName("a cursor is opaque, survives a round trip, and a made-up one is refused")
    void cursorsAreOpaqueAndValidated() {
        UUID id = UUID.randomUUID();
        Cursor original = new Cursor("Ceylon Tea 500g", id);
        String encoded = original.encode();

        assertThat(encoded).as("nothing readable leaks the sort column").doesNotContain("Ceylon");
        assertThat(Cursor.decode(encoded)).isEqualTo(original);

        // A name long enough to contain something that looks like a delimiter still round-trips,
        // which is why the id is fixed-width at the end rather than separated by a character.
        Cursor awkward = new Cursor("Tea | 500g | special", id);
        assertThat(Cursor.decode(awkward.encode())).isEqualTo(awkward);

        assertThatThrownBy(() -> Cursor.decode("not-a-real-cursor"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_CURSOR");
    }

    @Test
    @DisplayName("limit is clamped, so a huge page size cannot be asked for")
    void limitIsClamped() {
        assertThat(Cursor.clampLimit(1_000_000, 500, 500)).isEqualTo(500);
        assertThat(Cursor.clampLimit(0, 500, 500)).as("zero would loop forever").isEqualTo(500);
        assertThat(Cursor.clampLimit(-5, 500, 500)).isEqualTo(500);
        assertThat(Cursor.clampLimit(null, 500, 500)).isEqualTo(500);
        assertThat(Cursor.clampLimit(25, 500, 500)).isEqualTo(25);
    }

    // ==============================================================================
    // P7-05 · rate limiting that is not per-instance
    // ==============================================================================

    @Test
    @DisplayName("the limit is counted in the database, so every instance shares it")
    void rateLimitIsShared() {
        String key = "login:" + unique();

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(rateLimiter.tryAcquire(key, 10, Duration.ofMinutes(15)))
                    .as("attempt %d of 10", attempt)
                    .isTrue();
        }
        assertThat(rateLimiter.tryAcquire(key, 10, Duration.ofMinutes(15)))
                .as("the eleventh is refused")
                .isFalse();

        // The proof that it is shared rather than in one JVM's heap: the count is visible in SQL.
        // A second instance runs the same query against the same rows.
        Integer counted =
                jdbc.queryForObject(
                        "SELECT count(*) FROM rate_limit_attempt WHERE limit_key = ?",
                        Integer.class,
                        key);
        assertThat(counted).isEqualTo(10);

        rateLimiter.reset(key);
        assertThat(rateLimiter.tryAcquire(key, 10, Duration.ofMinutes(15)))
                .as("a successful login clears the history")
                .isTrue();
    }

    @Test
    @DisplayName("attempts outside the window stop counting")
    void theWindowSlides() {
        String key = "login:" + unique();
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire(key, 10, Duration.ofMinutes(15));
        }
        assertThat(rateLimiter.tryAcquire(key, 10, Duration.ofMinutes(15))).isFalse();

        // Backdate the attempts past the window rather than waiting fifteen minutes.
        jdbc.update(
                "UPDATE rate_limit_attempt SET attempted_at = attempted_at - interval '20 minutes'"
                        + " WHERE limit_key = ?",
                key);

        assertThat(rateLimiter.tryAcquire(key, 10, Duration.ofMinutes(15)))
                .as("old attempts have aged out of the window")
                .isTrue();
    }

    // ==============================================================================
    // P7-06 · the outbox
    // ==============================================================================

    @Test
    @DisplayName("sending enqueues rather than delivering, and the dispatcher delivers")
    void messagesGoThroughTheOutbox() {
        String recipient = unique() + "@test.local";

        notifications.sendEmail(recipient, "Subject line", "Body text");

        // Nothing has been delivered yet — that is the entire point.
        Mockito.verify(transport, Mockito.never())
                .deliver(Mockito.eq(recipient), Mockito.any(), Mockito.any());
        assertThat(pending(recipient)).as("the message is queued").isEqualTo(1);

        dispatcher.dispatchBatch();

        Mockito.verify(transport).deliver(recipient, "Subject line", "Body text");
        assertThat(pending(recipient)).isZero();
        assertThat(statusOf(recipient)).isEqualTo(OutboxMessage.SENT);
    }

    @Test
    @DisplayName("a rolled-back transaction delivers nothing")
    void rollbackTakesTheMessageWithIt() {
        String recipient = unique() + "@test.local";

        assertThatThrownBy(() -> enqueueThenFail(recipient)).isInstanceOf(IllegalStateException.class);

        assertThat(pending(recipient))
                .as("the row rolled back with the change that wanted it")
                .isZero();

        dispatcher.dispatchBatch();
        Mockito.verify(transport, Mockito.never())
                .deliver(Mockito.eq(recipient), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("a failing transport is retried later, not lost and not resent immediately")
    void failuresBackOff() {
        String recipient = unique() + "@test.local";
        Mockito.doThrow(new IllegalStateException("mail server down"))
                .when(transport)
                .deliver(Mockito.eq(recipient), Mockito.any(), Mockito.any());

        notifications.sendEmail(recipient, "Subject", "Body");
        dispatcher.dispatchBatch();

        OutboxMessage row = rowFor(recipient);
        assertThat(row.getStatus()).as("still pending, not lost").isEqualTo(OutboxMessage.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).contains("mail server down");
        assertThat(row.getNextAttemptAt())
                .as("backed off rather than hammered")
                .isAfter(java.time.Instant.now());

        // A second poll right away must not pick it up again — that is what the back-off is for.
        dispatcher.dispatchBatch();
        assertThat(rowFor(recipient).getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a permanently bad recipient is eventually given up on")
    void badRecipientsDoNotRetryForever() {
        String recipient = unique() + "@test.local";
        Mockito.doThrow(new IllegalStateException("no such mailbox"))
                .when(transport)
                .deliver(Mockito.eq(recipient), Mockito.any(), Mockito.any());

        notifications.sendEmail(recipient, "Subject", "Body");

        for (int attempt = 0; attempt < OutboxMessage.MAX_ATTEMPTS; attempt++) {
            // Bring the retry well into the past, not merely to now().
            //
            // `now()` is the database clock; the poller compares against an Instant from the JVM
            // clock. A few microseconds of skew between them leaves the row fractionally in the
            // future and the poll skips it — which is exactly what happened, leaving four attempts
            // where five were expected. An hour is past by any clock, and sorts the row to the
            // front of the batch as a bonus.
            jdbc.update(
                    "UPDATE outbox_message SET next_attempt_at = now() - interval '1 hour'"
                            + " WHERE recipient = ?",
                    recipient);
            dispatcher.dispatchBatch();
        }

        OutboxMessage row = rowFor(recipient);
        assertThat(row.getStatus()).isEqualTo(OutboxMessage.FAILED);
        assertThat(row.getAttempts()).isEqualTo(OutboxMessage.MAX_ATTEMPTS);

        // And it stays out of the queue, rather than filling the log forever.
        jdbc.update(
                "UPDATE outbox_message SET next_attempt_at = now() - interval '1 hour'"
                        + " WHERE recipient = ?",
                recipient);
        assertThat(dispatcher.dispatchBatch()).isZero();
    }

    // ==============================================================================
    // Fixtures
    // ==============================================================================

    /**
     * Enqueues and then throws, so the surrounding transaction rolls back.
     *
     * <p>Driven through {@link TransactionTemplate} rather than an {@code @Transactional} method
     * on this class. A self-invocation never passes through the Spring proxy, so the annotation
     * would do nothing and the test would pass for the wrong reason — which is exactly what it did
     * the first time it was written.
     */
    private void enqueueThenFail(String recipient) {
        transactions.executeWithoutResult(
                status -> {
                    notifications.sendEmail(recipient, "Subject", "Body");
                    throw new IllegalStateException("the business change failed after enqueuing");
                });
    }

    /**
     * Walks only the items this test created.
     *
     * <p>The test database accumulates a catalogue across every run, so paging from the very start
     * would be thousands of rows to reach thirty. Names are prefixed with a unique tag and the walk
     * starts at a cursor immediately before them, which is a fair test of the mechanism — the
     * cursor does not know or care that it began in the middle.
     */
    private Set<UUID> walkSlice(String tag, int pageSize) {
        Set<UUID> seen = new LinkedHashSet<>();
        // Just before the first tagged name, in (name, id) order.
        Cursor cursor = new Cursor(tag, new UUID(0L, 0L));
        int guard = 0;

        while (guard++ < 50) {
            List<Item> fetched = items.page(true, cursor, pageSize);
            List<Item> rows = fetched.size() > pageSize ? fetched.subList(0, pageSize) : fetched;

            List<Item> mine = rows.stream().filter(item -> item.getName().startsWith(tag)).toList();
            mine.forEach(item -> seen.add(item.getId()));

            // Past the end of the tagged block, or past the end of the table.
            if (mine.size() < rows.size() || fetched.size() <= pageSize) {
                break;
            }
            Item last = rows.get(rows.size() - 1);
            cursor = new Cursor(last.getName(), last.getId());
        }
        return seen;
    }

    private long pending(String recipient) {
        Long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE recipient = ? AND status = 'pending'",
                        Long.class,
                        recipient);
        return count == null ? 0 : count;
    }

    private String statusOf(String recipient) {
        return jdbc.queryForObject(
                "SELECT status FROM outbox_message WHERE recipient = ?", String.class, recipient);
    }

    private OutboxMessage rowFor(String recipient) {
        UUID id =
                jdbc.queryForObject(
                        "SELECT id FROM outbox_message WHERE recipient = ?", UUID.class, recipient);
        return outbox.findById(id).orElseThrow();
    }

    private String unique() {
        return "P7-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Item newItem(String name) {
        return provisioning.createWithOpeningStock(
                "P7-" + UUID.randomUUID(),
                new ItemDetails(
                        name,
                        null,
                        null,
                        new BigDecimal("10.00"),
                        new BigDecimal("20.00"),
                        null,
                        null,
                        0),
                new OpeningStock(store, 0),
                actor());
    }

    private UUID actor() {
        AppUser user = new AppUser();
        user.setEmail("p7-" + UUID.randomUUID() + "@test.local");
        user.setFullName("Scale-up fixture");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatusValue(UserStatus.ACTIVE);
        return users.saveAndFlush(user).getId();
    }
}
