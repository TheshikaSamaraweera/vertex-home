package com.democode.mlmsittu.onboarding.internal.registration;

import com.democode.mlmsittu.shared.api.Cursor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every distributor, as one table an administrator can scan.
 *
 * <p>Written as SQL for the same reason the reports are: it spans accounts, distributors,
 * registrations and stage progress, and returns a shape no entity has. Loading four object graphs
 * to build one row would be slower and no clearer.
 *
 * <p><b>Admin only.</b> This is the view that ignores the one-level-up, one-level-down boundary the
 * portal enforces, so the endpoint in front of it says so.
 */
@Service
public class DistributorDirectoryService {

    private final JdbcTemplate jdbc;

    public DistributorDirectoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One person, summarised.
     *
     * @param joinedAt account creation — distinct from {@code registeredAt}, when they applied, and
     *     {@code approvedAt}, when they became a distributor. An admin asking "how long has this
     *     person been with us" means the first; an auditor asking "when were they admitted" means
     *     the third.
     * @param registrationStatus null when they have an account but never applied
     */
    public record DistributorRow(
            UUID userId,
            UUID distributorId,
            String businessId,
            String fullName,
            String email,
            String mobile,
            String accountStatus,
            String distributorStatus,
            String registrationStatus,
            String referrerBusinessId,
            int directReferrals,
            int stagesCompleted,
            boolean bonusEligible,
            Instant joinedAt,
            Instant registeredAt,
            Instant approvedAt,
            /** When the membership lapses. Null for anybody not yet approved. */
            Instant expiresAt) {}

    /** The columns of one row. Shared by the full list and the paged one so they cannot drift. */
    private static final String SELECT_ROW =
            """
            SELECT u.id                AS user_id,
                   d.id                AS distributor_id,
                   d.business_id,
                   u.full_name,
                   u.email,
                   u.mobile,
                   u.status            AS account_status,
                   d.status            AS distributor_status,
                   reg.status          AS registration_status,
                   reg.referrer_business_id,
                   COALESCE(d.direct_child_count, 0)   AS direct_referrals,
                   COALESCE(sp.stages_completed, 0)    AS stages_completed,
                   COALESCE(sp.bonus_stage_eligible, false) AS bonus_eligible,
                   u.created_at        AS joined_at,
                   reg.submitted_at    AS registered_at,
                   d.approved_at,
                   d.expires_at
            """;

    /**
     * Who is in the directory, and the search over them. Three parameters in order: include
     * applicants, then the search term three times — null for no search.
     */
    private static final String FROM_WHERE =
            """
            FROM app_user u
            JOIN user_role ur ON ur.user_id = u.id
            JOIN app_role r   ON r.id = ur.role_id AND r.code = 'DISTRIBUTOR'
            LEFT JOIN distributor d ON d.user_id = u.id
            LEFT JOIN referral_stage_progress sp ON sp.distributor_id = d.id
            -- The most recent application, which is the one that describes where they are now.
            LEFT JOIN LATERAL (
                SELECT status, referrer_business_id, submitted_at
                FROM registration
                WHERE user_id = u.id
                ORDER BY created_at DESC
                LIMIT 1
            ) reg ON true
            WHERE (? = true OR d.id IS NOT NULL)
              AND (CAST(? AS text) IS NULL
                   OR lower(u.full_name) LIKE CAST(? AS text)
                   OR lower(u.email) LIKE CAST(? AS text)
                   OR lower(COALESCE(d.business_id, '')) LIKE CAST(? AS text))
            """;

    /**
     * @param search matched against name, e-mail and Business ID
     * @param includeApplicants people who have signed up but are not distributors yet. On by
     *     default, because the pending queue is exactly who an admin is looking for.
     */
    @Transactional(readOnly = true)
    public List<DistributorRow> list(String search, boolean includeApplicants) {
        String term = termOf(search);
        return jdbc.query(
                SELECT_ROW + FROM_WHERE + "ORDER BY d.business_id NULLS LAST, u.created_at DESC",
                (rs, rowNum) -> rowOf(rs),
                includeApplicants,
                term, term, term, term);
    }

    /**
     * One page of the directory, newest account first.
     *
     * <p>Keyed on {@code (created_at, id)} rather than the Business ID the full list sorts by:
     * applicants have no Business ID yet, and a sort key that is null for part of the list cannot
     * say where a page ends. Newest first is also the order an administrator works in — the
     * people who just signed up are the ones waiting on them.
     *
     * @param after null for the first page
     * @param limit rows to fetch — one more than the page shows, to learn whether more follow
     */
    @Transactional(readOnly = true)
    public List<DistributorRow> page(
            String search, boolean includeApplicants, Cursor after, int limit) {
        String term = termOf(search);
        if (after == null) {
            return jdbc.query(
                    SELECT_ROW + FROM_WHERE + "ORDER BY u.created_at DESC, u.id DESC LIMIT ?",
                    (rs, rowNum) -> rowOf(rs),
                    includeApplicants,
                    term, term, term, term,
                    limit);
        }
        return jdbc.query(
                SELECT_ROW
                        + FROM_WHERE
                        + """
                          AND (u.created_at, u.id) < (CAST(? AS timestamptz), CAST(? AS uuid))
                        ORDER BY u.created_at DESC, u.id DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> rowOf(rs),
                includeApplicants,
                term, term, term, term,
                after.sortKey(),
                after.id(),
                limit);
    }

    /** Headline figures for the whole directory under the same search, however it is paged. */
    public record DirectoryCounts(long total, long active, long waiting) {}

    @Transactional(readOnly = true)
    public DirectoryCounts counts(String search, boolean includeApplicants) {
        String term = termOf(search);
        return jdbc.queryForObject(
                """
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE d.status = 'active') AS active,
                       count(*) FILTER (WHERE reg.status IN
                                        ('submitted', 'under_review', 'resubmit_required')) AS waiting
                """
                        + FROM_WHERE,
                (rs, rowNum) ->
                        new DirectoryCounts(
                                rs.getLong("total"), rs.getLong("active"), rs.getLong("waiting")),
                includeApplicants,
                term, term, term, term);
    }

    private static String termOf(String search) {
        return search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase() + "%";
    }

    private DistributorRow rowOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DistributorRow(
                rs.getObject("user_id", UUID.class),
                rs.getObject("distributor_id", UUID.class),
                rs.getString("business_id"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("mobile"),
                rs.getString("account_status"),
                rs.getString("distributor_status"),
                rs.getString("registration_status"),
                rs.getString("referrer_business_id"),
                rs.getInt("direct_referrals"),
                rs.getInt("stages_completed"),
                rs.getBoolean("bonus_eligible"),
                instant(rs.getTimestamp("joined_at")),
                instant(rs.getTimestamp("registered_at")),
                instant(rs.getTimestamp("approved_at")),
                instant(rs.getTimestamp("expires_at")));
    }

    /**
     * What this person has actually done, newest first.
     *
     * <p>Read from {@code audit_log}, which already records every business action with its actor —
     * so "activity" needed no new table, only somewhere to show it. Capped, because a busy account
     * has thousands and a profile page wants the recent ones.
     */
    @Transactional(readOnly = true)
    public List<ActivityEntry> activityOf(UUID userId, int limit) {
        return jdbc.query(
                """
                SELECT action, entity_type, entity_id, created_at
                FROM audit_log
                WHERE actor_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) ->
                        new ActivityEntry(
                                rs.getString("action"),
                                rs.getString("entity_type"),
                                rs.getObject("entity_id", UUID.class),
                                instant(rs.getTimestamp("created_at"))),
                userId,
                limit);
    }

    public record ActivityEntry(String action, String entityType, UUID entityId, Instant at) {}

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
