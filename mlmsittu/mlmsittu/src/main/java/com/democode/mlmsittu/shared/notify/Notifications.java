package com.democode.mlmsittu.shared.notify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * In-app notifications: the bell, its badge, and the history behind it.
 *
 * <p>Separate from {@link NotificationSender}, which sends email. A notification is addressed to a
 * user rather than to an address, is never delivered anywhere, and lives until it is read. Most of
 * this client's customers have no email address at all, so for them this is the only channel that
 * reaches them.
 *
 * <p>Raising one is deliberately best-effort: see {@link #raise}. A notification is a courtesy, and
 * failing to record one must never roll back the thing it was announcing.
 */
@Service
public class Notifications {

    private static final Logger log = LoggerFactory.getLogger(Notifications.class);

    /** Kinds, so call sites do not invent spellings and the UI can match on them. */
    public static final String REGISTRATION_APPROVED = "REGISTRATION_APPROVED";
    public static final String REGISTRATION_REJECTED = "REGISTRATION_REJECTED";
    public static final String REGISTRATION_SUBMITTED = "REGISTRATION_SUBMITTED";
    public static final String REFERRAL_JOINED = "REFERRAL_JOINED";
    public static final String STAGES_COMPLETE = "STAGES_COMPLETE";
    public static final String REWARD_READY = "REWARD_READY";
    public static final String REWARD_ISSUED = "REWARD_ISSUED";
    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";
    public static final String MEMBERSHIP_EXPIRING = "MEMBERSHIP_EXPIRING";
    public static final String STOCK_LOW = "STOCK_LOW";
    public static final String PURCHASE_ORDER_SENT = "PURCHASE_ORDER_SENT";

    private final JdbcTemplate jdbc;
    private final NotificationStream stream;

    public Notifications(JdbcTemplate jdbc, NotificationStream stream) {
        this.jdbc = jdbc;
        this.stream = stream;
    }

    /** One notification, as the bell and the history page show it. */
    public record Notification(
            UUID id,
            String kind,
            String title,
            String body,
            String link,
            Instant readAt,
            Instant createdAt) {}

    // ------------------------------------------------------------------ raising

    /**
     * Records a notification for one person.
     *
     * <p><b>Never throws.</b> Every call site is in the middle of doing something that matters —
     * approving a registration, issuing a pack — and a notification is the announcement, not the
     * event. Letting a failure here propagate would roll back an approval because the bell could
     * not be updated, which is precisely backwards.
     *
     * <p>The live push happens after the surrounding transaction commits, not now. Pushing inline
     * would deliver "your registration was approved" from a transaction that then rolled back, and
     * the recipient would refresh to find it had not.
     */
    public void raise(UUID userId, String kind, String title, String body, String link) {
        if (userId == null) {
            return;
        }
        try {
            UUID id =
                    jdbc.queryForObject(
                            """
                            INSERT INTO notification (user_id, kind, title, body, link)
                            VALUES (?, ?, ?, ?, ?)
                            RETURNING id
                            """,
                            UUID.class,
                            userId,
                            kind,
                            title,
                            body,
                            link);

            pushAfterCommit(userId, new Notification(id, kind, title, body, link, null, Instant.now()));

        } catch (RuntimeException failure) {
            // Logged and swallowed. See the javadoc: the alternative is an approval that fails
            // because a courtesy message could not be written.
            log.warn("Could not raise notification '{}' for user {}", kind, userId, failure);
        }
    }

    /** The same notification to several people — reviewers, administrators. */
    public void raiseAll(List<UUID> userIds, String kind, String title, String body, String link) {
        userIds.forEach(userId -> raise(userId, kind, title, body, link));
    }

    private void pushAfterCommit(UUID userId, Notification notification) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            stream.push(userId, notification);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        stream.push(userId, notification);
                    }
                });
    }

    // ------------------------------------------------------------------ reading

    /**
     * One person's notifications, newest first.
     *
     * <p>Capped rather than paginated. A notification list is read by scrolling a short way and
     * then losing interest; nobody pages to the bottom of one, and the audit log is where "what
     * happened in March" is answered.
     */
    @Transactional(readOnly = true)
    public List<Notification> list(UUID userId, int limit) {
        return jdbc.query(
                """
                SELECT id, kind, title, body, link, read_at, created_at
                  FROM notification
                 WHERE user_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """,
                (rs, row) ->
                        new Notification(
                                rs.getObject("id", UUID.class),
                                rs.getString("kind"),
                                rs.getString("title"),
                                rs.getString("body"),
                                rs.getString("link"),
                                rs.getTimestamp("read_at") == null
                                        ? null
                                        : rs.getTimestamp("read_at").toInstant(),
                                rs.getTimestamp("created_at").toInstant()),
                userId,
                Math.min(Math.max(limit, 1), 200));
    }

    @Transactional(readOnly = true)
    public int unreadCount(UUID userId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM notification WHERE user_id = ? AND read_at IS NULL",
                        Integer.class,
                        userId);
        return count == null ? 0 : count;
    }

    /**
     * Marks one as read.
     *
     * <p>Scoped by user as well as by id, so a guessed identifier marks nothing. Without that the
     * endpoint would let anybody clear anybody else's bell — harmless in itself, and exactly the
     * shape of hole that turns out not to be harmless somewhere else.
     */
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        jdbc.update(
                "UPDATE notification SET read_at = now() "
                        + "WHERE id = ? AND user_id = ? AND read_at IS NULL",
                notificationId,
                userId);
    }

    @Transactional
    public void markAllRead(UUID userId) {
        jdbc.update(
                "UPDATE notification SET read_at = now() WHERE user_id = ? AND read_at IS NULL",
                userId);
    }
}
