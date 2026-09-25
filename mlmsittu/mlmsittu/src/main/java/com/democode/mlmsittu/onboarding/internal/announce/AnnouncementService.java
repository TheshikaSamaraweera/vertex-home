package com.democode.mlmsittu.onboarding.internal.announce;

import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import com.democode.mlmsittu.shared.notify.Notifications;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announcements — what the office puts in front of every customer.
 *
 * <h2>The body is structure, not markup</h2>
 *
 * <p>The document arrives and leaves as typed blocks: heading, paragraph, list, image, with inline
 * marks limited to bold, italic and highlight. No HTML in either direction.
 *
 * <p>That is a security decision rather than a stylistic one. HTML authored by one person and
 * rendered in everybody else's browser is an XSS hole held shut by a sanitiser, and sanitisers get
 * bypassed. Administrators are trusted, but trust describes intent, not whether an account is ever
 * compromised — and the blast radius is every customer at once. With structure there is nothing to
 * sanitise: the browser walks the tree and emits elements, an unknown block is skipped, and no
 * code path in the application can produce {@code innerHTML}.
 *
 * <p>{@link #validateBody} is what keeps that true on the way in. Without it, somebody could post
 * a block whose "type" is anything at all and rely on a future renderer being careless with it.
 */
@Service
public class AnnouncementService {

    /**
     * Block types the renderer knows.
     *
     * <p>Checked on write as well as read. A body may only contain these, so a document that
     * reaches the portal cannot carry a shape nobody has thought about — and adding a new block
     * type is a deliberate change here rather than something that happens by accident.
     */
    private static final List<String> ALLOWED_BLOCKS =
            List.of("heading", "paragraph", "bulletList", "orderedList", "listItem", "text", "image", "hardBreak");

    /** Inline marks. Bold, italic, highlight — and nothing that can carry a URL or a script. */
    private static final List<String> ALLOWED_MARKS = List.of("bold", "italic", "highlight", "underline");

    /** Enough for a long notice, short of somebody pasting a book into the portal home page. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final JdbcTemplate jdbc;
    private final Notifications notifications;

    public AnnouncementService(JdbcTemplate jdbc, Notifications notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ shapes

    public static final List<String> CATEGORIES = List.of("news", "offer", "new_arrival", "event");
    public static final List<String> AUDIENCES = List.of("everyone", "members");

    /**
     * Where a button may go: pages of the portal, by name. Never a URL — a notice carries no link
     * anybody else chose, and the frontend maps each name to its own route.
     */
    public static final List<String> CTA_TARGETS =
            List.of("item_packs", "registration", "referrals", "stages", "offers");

    /**
     * @param category {@code news}, {@code offer}, {@code new_arrival} or {@code event}
     * @param featured shown in the banner at the top of the customer's dashboard
     * @param audience {@code everyone} or {@code members}
     * @param ctaTarget one of {@link #CTA_TARGETS}; null with {@code ctaLabel} for no button
     * @param views how many people have seen it — once each. Null in a customer's view.
     * @param clicks how many people pressed its button — once each. Null in a customer's view.
     * @param seen whether this customer has seen it. Null in the office's view.
     */
    public record Announcement(
            UUID id,
            String title,
            String subtitle,
            String body,
            UUID imageId,
            Instant publishedAt,
            Instant expiresAt,
            String authorName,
            Instant createdAt,
            String category,
            boolean featured,
            String audience,
            String ctaLabel,
            String ctaTarget,
            Long views,
            Long clicks,
            Boolean seen) {}

    /** Everything an administrator writes. The marketing fields default to a plain notice. */
    public record Fields(
            String title,
            String subtitle,
            String body,
            UUID imageId,
            Instant expiresAt,
            String category,
            boolean featured,
            String audience,
            String ctaLabel,
            String ctaTarget) {

        /** A plain notice for members, as announcements were before V35. */
        public static Fields plain(
                String title, String subtitle, String body, UUID imageId, Instant expiresAt) {
            return new Fields(
                    title, subtitle, body, imageId, expiresAt, "news", false, "members", null, null);
        }
    }

    // ------------------------------------------------------------------ writing

    public UUID create(
            String title, String subtitle, String body, UUID imageId, Instant expiresAt, UUID authorId) {
        return create(Fields.plain(title, subtitle, body, imageId, expiresAt), authorId);
    }

    @Transactional
    @Audited(action = "ANNOUNCEMENT_CREATED", entityType = "announcement", auditFailures = true)
    public UUID create(Fields fields, UUID authorId) {
        Fields clean = validate(fields);

        UUID id =
                jdbc.queryForObject(
                        """
                        INSERT INTO announcement (title, subtitle, body, image_id, expires_at,
                                                  category, featured, audience, cta_label,
                                                  cta_target, created_by)
                        VALUES (?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                        UUID.class,
                        clean.title(),
                        clean.subtitle(),
                        clean.body(),
                        clean.imageId(),
                        timestamp(clean.expiresAt()),
                        clean.category(),
                        clean.featured(),
                        clean.audience(),
                        clean.ctaLabel(),
                        clean.ctaTarget(),
                        authorId);

        AuditContext.record(id, null, Map.of("title", clean.title()));
        return id;
    }

    public void update(
            UUID id, String title, String subtitle, String body, UUID imageId, Instant expiresAt) {
        // The old call keeps whatever marketing settings the notice already has.
        Announcement current = get(id);
        update(
                id,
                new Fields(
                        title,
                        subtitle,
                        body,
                        imageId,
                        expiresAt,
                        current.category(),
                        current.featured(),
                        current.audience(),
                        current.ctaLabel(),
                        current.ctaTarget()));
    }

    /**
     * Changes the wording, picture, end date or presentation. Never whether it is out.
     *
     * <p>Publishing is its own step, and an edit must not be a second way round it. An announcement
     * that has been taken down keeps its end date whatever the form sends: the editor pre-fills
     * that date, and clearing it or moving it forward used to put the notice straight back on
     * every customer's home page — already "published", so with no send step and no notification.
     */
    @Transactional
    @Audited(action = "ANNOUNCEMENT_UPDATED", entityType = "announcement", auditFailures = true)
    public void update(UUID id, Fields fields) {
        Fields clean = validate(fields);

        int updated =
                jdbc.update(
                        """
                        UPDATE announcement
                           SET title = ?, subtitle = ?, body = CAST(? AS JSONB),
                               image_id = ?,
                               expires_at = CASE
                                   WHEN published_at IS NOT NULL AND expires_at <= now()
                                       THEN expires_at
                                   ELSE ?
                               END,
                               category = ?, featured = ?, audience = ?,
                               cta_label = ?, cta_target = ?,
                               updated_at = now()
                         WHERE id = ?
                        """,
                        clean.title(),
                        clean.subtitle(),
                        clean.body(),
                        clean.imageId(),
                        timestamp(clean.expiresAt()),
                        clean.category(),
                        clean.featured(),
                        clean.audience(),
                        clean.ctaLabel(),
                        clean.ctaTarget(),
                        id);

        if (updated == 0) {
            throw new NotFoundException("ANNOUNCEMENT_NOT_FOUND", "No such announcement.");
        }
        AuditContext.record(id, null, Map.of("title", clean.title()));
    }

    /**
     * Sends it.
     *
     * <p>Separate from creating it, so an administrator writing a long notice over two sittings is
     * not broadcasting the half-finished version in between. Publishing is also what raises the
     * notification, so the bell rings once — at the moment the thing became real — rather than on
     * every save. It rings for the audience the post is for, and nobody else.
     */
    @Transactional
    @Audited(action = "ANNOUNCEMENT_PUBLISHED", entityType = "announcement", auditFailures = true)
    public void publish(UUID id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT title, audience FROM announcement WHERE id = ? AND published_at IS NULL",
                        id);

        if (rows.isEmpty()) {
            // Either it does not exist or it is already out. Both mean "do not send it again",
            // and sending a second notification for one notice is the thing to avoid here.
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ALREADY_PUBLISHED",
                    "That announcement has already been published.");
        }
        String title = (String) rows.get(0).get("title");
        String audience = (String) rows.get(0).get("audience");

        jdbc.update("UPDATE announcement SET published_at = now(), updated_at = now() WHERE id = ?", id);

        // Customers, not staff: this is for the people the business sells to, and an
        // administrator already knows — they wrote it.
        List<UUID> recipients =
                "everyone".equals(audience)
                        ? jdbc.queryForList(
                                """
                                SELECT u.id
                                  FROM app_user u
                                 WHERE u.status = 'active'
                                   AND EXISTS (SELECT 1 FROM user_role ur JOIN app_role r ON r.id = ur.role_id
                                                WHERE ur.user_id = u.id AND r.code = 'DISTRIBUTOR')
                                   AND NOT EXISTS (SELECT 1 FROM user_role ur JOIN app_role r ON r.id = ur.role_id
                                                    WHERE ur.user_id = u.id AND r.code <> 'DISTRIBUTOR')
                                """,
                                UUID.class)
                        : jdbc.queryForList(
                                """
                                SELECT DISTINCT d.user_id
                                  FROM distributor d
                                 WHERE d.status = 'active' AND d.deleted_at IS NULL
                                   AND (d.expires_at IS NULL OR d.expires_at > now())
                                """,
                                UUID.class);

        notifications.raiseAll(recipients, Notifications.ANNOUNCEMENT, title, null, "/portal/offers");

        AuditContext.record(id, null, Map.of("recipients", String.valueOf(recipients.size())));
    }

    /** Takes it down without deleting it, so the record of what was said survives. */
    @Transactional
    @Audited(action = "ANNOUNCEMENT_WITHDRAWN", entityType = "announcement", auditFailures = true)
    public void withdraw(UUID id) {
        jdbc.update(
                "UPDATE announcement SET expires_at = now(), updated_at = now() WHERE id = ?", id);
        AuditContext.record(id, null, null);
    }

    @Transactional
    @Audited(action = "ANNOUNCEMENT_DELETED", entityType = "announcement", auditFailures = true)
    public void delete(UUID id) {
        jdbc.update("DELETE FROM announcement WHERE id = ?", id);
        AuditContext.record(id, null, null);
    }

    // ------------------------------------------------------------------ engagement

    /**
     * Records that a customer saw a post, or pressed its button. Once per person each — the numbers
     * are people reached, not page loads — and only for a post that customer can actually see.
     */
    @Transactional
    public void recordEngagement(UUID announcementId, UUID userId, String kind) {
        if (!"view".equals(kind) && !"click".equals(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_KIND", "Unknown engagement.");
        }
        boolean visible =
                listLiveFor(userId).stream().anyMatch(a -> a.id().equals(announcementId));
        if (!visible) {
            throw new NotFoundException("ANNOUNCEMENT_NOT_FOUND", "No such announcement.");
        }
        jdbc.update(
                """
                INSERT INTO announcement_engagement (announcement_id, user_id, kind)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                announcementId,
                userId,
                kind);
    }

    // ------------------------------------------------------------------ reading

    private static final String SELECT =
            """
            SELECT a.id, a.title, a.subtitle, a.body::TEXT AS body, a.image_id,
                   a.published_at, a.expires_at, u.full_name AS author_name, a.created_at,
                   a.category, a.featured, a.audience, a.cta_label, a.cta_target
            """;

    /** Everything, drafts included, with reach and clicks. For the administrator's list. */
    @Transactional(readOnly = true)
    public List<Announcement> listAll() {
        return jdbc.query(
                SELECT
                        + """
                        ,
                        (SELECT count(*) FROM announcement_engagement e
                          WHERE e.announcement_id = a.id AND e.kind = 'view') AS views,
                        (SELECT count(*) FROM announcement_engagement e
                          WHERE e.announcement_id = a.id AND e.kind = 'click') AS clicks,
                        NULL::BOOLEAN AS seen
                          FROM announcement a
                          JOIN app_user u ON u.id = a.created_by
                         ORDER BY a.created_at DESC
                         LIMIT 200
                        """,
                this::mapRow);
    }

    /**
     * Every live post, whatever its audience: published, not expired, newest first.
     *
     * <p>Expiry is compared to the clock here rather than by a job that flips a flag, for the same
     * reason membership expiry is — a date passing is not an event anybody performs, and a sweep
     * would be wrong for the hours between it running and the date arriving.
     */
    @Transactional(readOnly = true)
    public List<Announcement> listLive() {
        return jdbc.query(
                SELECT
                        + """
                        , NULL::BIGINT AS views, NULL::BIGINT AS clicks, NULL::BOOLEAN AS seen
                          FROM announcement a
                          JOIN app_user u ON u.id = a.created_by
                         WHERE a.published_at IS NOT NULL
                           AND (a.expires_at IS NULL OR a.expires_at > now())
                         ORDER BY a.featured DESC, a.published_at DESC
                         LIMIT 50
                        """,
                this::mapRow);
    }

    /**
     * What one signed-in person sees: the live posts for their audience, and whether they have
     * seen each. A member sees everything; somebody not yet approved sees only posts for everyone.
     */
    @Transactional(readOnly = true)
    public List<Announcement> listLiveFor(UUID userId) {
        return jdbc.query(
                SELECT
                        + """
                        , NULL::BIGINT AS views, NULL::BIGINT AS clicks,
                        EXISTS (SELECT 1 FROM announcement_engagement e
                                 WHERE e.announcement_id = a.id AND e.user_id = ?
                                   AND e.kind = 'view') AS seen
                          FROM announcement a
                          JOIN app_user u ON u.id = a.created_by
                         WHERE a.published_at IS NOT NULL
                           AND (a.expires_at IS NULL OR a.expires_at > now())
                           AND (a.audience = 'everyone'
                                OR EXISTS (SELECT 1 FROM distributor d
                                            WHERE d.user_id = ? AND d.status = 'active'
                                              AND d.deleted_at IS NULL))
                         ORDER BY a.featured DESC, a.published_at DESC
                         LIMIT 50
                        """,
                this::mapRow,
                userId,
                userId);
    }

    @Transactional(readOnly = true)
    public Announcement get(UUID id) {
        List<Announcement> found =
                jdbc.query(
                        SELECT
                                + """
                                ,
                                (SELECT count(*) FROM announcement_engagement e
                                  WHERE e.announcement_id = a.id AND e.kind = 'view') AS views,
                                (SELECT count(*) FROM announcement_engagement e
                                  WHERE e.announcement_id = a.id AND e.kind = 'click') AS clicks,
                                NULL::BOOLEAN AS seen
                                  FROM announcement a
                                  JOIN app_user u ON u.id = a.created_by
                                 WHERE a.id = ?
                                """,
                        this::mapRow,
                        id);
        if (found.isEmpty()) {
            throw new NotFoundException("ANNOUNCEMENT_NOT_FOUND", "No such announcement.");
        }
        return found.get(0);
    }

    private Announcement mapRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Announcement(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("body"),
                rs.getObject("image_id", UUID.class),
                instant(rs.getTimestamp("published_at")),
                instant(rs.getTimestamp("expires_at")),
                rs.getString("author_name"),
                instant(rs.getTimestamp("created_at")),
                rs.getString("category"),
                rs.getBoolean("featured"),
                rs.getString("audience"),
                rs.getString("cta_label"),
                rs.getString("cta_target"),
                rs.getObject("views", Long.class),
                rs.getObject("clicks", Long.class),
                rs.getObject("seen", Boolean.class));
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Checks and tidies everything an administrator sent. */
    private Fields validate(Fields fields) {
        validateBody(fields.body());
        String category = fields.category() == null ? "news" : fields.category();
        String audience = fields.audience() == null ? "members" : fields.audience();
        if (!CATEGORIES.contains(category)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY", "Unknown category.");
        }
        if (!AUDIENCES.contains(audience)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AUDIENCE", "Unknown audience.");
        }
        String ctaLabel = blankToNull(fields.ctaLabel());
        String ctaTarget = blankToNull(fields.ctaTarget());
        if ((ctaLabel == null) != (ctaTarget == null)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CTA_INCOMPLETE",
                    "A button needs both its text and where it goes.");
        }
        if (ctaTarget != null && !CTA_TARGETS.contains(ctaTarget)) {
            // Not a URL, and not anything but a page of the portal: see CTA_TARGETS.
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_CTA_TARGET", "A button can only open a portal page.");
        }
        return new Fields(
                fields.title().trim(),
                blankToNull(fields.subtitle()),
                fields.body(),
                fields.imageId(),
                fields.expiresAt(),
                category,
                fields.featured(),
                audience,
                ctaLabel,
                ctaTarget);
    }

    // ------------------------------------------------------------------ the guard

    /**
     * Refuses a body containing anything the renderer does not know.
     *
     * <p>The renderer skips unknown blocks, so this is the second of two defences rather than the
     * only one — but it is the one that keeps the stored data honest. Without it the table would
     * accumulate documents shaped however a client felt like sending them, and the first person to
     * write a renderer that trusts its input would ship a hole that was already in the database.
     *
     * <p>A blunt string scan rather than a parse. Walking the tree properly would mean modelling
     * every node shape here as well as in the client, and the question being asked is narrow
     * enough not to need it: does this document mention any type or mark that is not on the list.
     */
    private void validateBody(String body) {
        if (body == null || body.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "EMPTY_BODY", "An announcement needs something in it.");
        }
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "BODY_TOO_LARGE",
                    "That announcement is too long. Keep it under 64 KB.");
        }

        for (String type : extractValues(body, "\"type\":")) {
            // "doc" is the root the editor wraps everything in.
            if (!"doc".equals(type) && !ALLOWED_BLOCKS.contains(type) && !ALLOWED_MARKS.contains(type)) {
                ApiException rejected =
                        new ApiException(
                                HttpStatus.BAD_REQUEST,
                                "UNSUPPORTED_CONTENT",
                                "That announcement contains formatting this cannot show.");
                rejected.with("type", type);
                throw rejected;
            }
        }
    }

    /** Every string value following {@code key} in the JSON text. */
    private static List<String> extractValues(String json, String key) {
        List<String> values = new java.util.ArrayList<>();
        int at = 0;
        while ((at = json.indexOf(key, at)) >= 0) {
            int quote = json.indexOf('"', at + key.length());
            if (quote < 0) break;
            int end = json.indexOf('"', quote + 1);
            if (end < 0) break;
            values.add(json.substring(quote + 1, end));
            at = end;
        }
        return values;
    }
}
