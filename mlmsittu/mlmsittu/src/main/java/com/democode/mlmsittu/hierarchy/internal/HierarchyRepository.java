package com.democode.mlmsittu.hierarchy.internal;

import com.democode.mlmsittu.hierarchy.api.DistributorNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The referral graph, queried with native SQL.
 *
 * <h2>Why this module avoids Hibernate</h2>
 *
 * PostgreSQL's {@code ltree} has no Hibernate type. The architecture (§2.2) isolates the module
 * rather than writing a custom {@code UserType} to own forever, and specifies jOOQ for the job.
 *
 * <p>This uses {@code JdbcTemplate} instead. The requirement being met is "keep {@code ltree} away
 * from the ORM", which either satisfies; jOOQ's own value is a type-safe DSL generated from the
 * schema, and that codegen step needs a live database at build time for six queries. The
 * observable requirement of P4-03 — that the GIST index is used rather than a sequential scan — is
 * identical either way, and {@code HierarchyIndexTest} checks it with {@code EXPLAIN}. Swapping to
 * jOOQ later replaces this one class.
 *
 * <p>Every query is parameterised. {@code ltree} operators take their operand as a bind parameter
 * cast in SQL, never concatenated — architecture §7.3 permits no string-built SQL anywhere.
 */
@Repository
public class HierarchyRepository {

    /**
     * Select list, FROM and joins in one piece.
     *
     * <p>Deliberately not split into "columns" and "the rest". The select list references
     * {@code p}, so a query that appended the columns without the join produced valid-looking Java
     * and SQL that fails at runtime — which is exactly what happened the first time this was
     * written as two constants. Keeping them inseparable makes that mistake impossible.
     *
     * <p>Stage progress is joined rather than fetched per node: the tree screen draws hundreds of
     * nodes at once, and asking for each one's stage separately was one HTTP request per visible
     * distributor. A left join because a distributor approved before the stage table existed has
     * no row, and that means zero rather than missing.
     */
    private static final String SELECT_NODE =
            """
            SELECT d.id, d.business_id, d.user_id, u.full_name, d.referred_by,
                   d.path::text AS path_text, d.status, d.direct_child_count, d.approved_at,
                   COALESCE(p.stages_completed, 0)         AS stages_completed,
                   COALESCE(p.bonus_stage_eligible, false) AS bonus_stage_eligible,
                   d.item_set_id
            FROM distributor d
            JOIN app_user u ON u.id = d.user_id
            LEFT JOIN referral_stage_progress p ON p.distributor_id = d.id
            """;

    private final JdbcTemplate jdbc;

    public HierarchyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RowMapper<DistributorNode> mapper(int rootDepth) {
        return (rs, rowNum) -> {
            String path = rs.getString("path_text");
            int depth = path == null ? 0 : Math.max(path.split("\\.").length - rootDepth, 0);
            return new DistributorNode(
                    rs.getObject("id", UUID.class),
                    rs.getString("business_id"),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("full_name"),
                    rs.getObject("referred_by", UUID.class),
                    path,
                    rs.getString("status"),
                    rs.getInt("direct_child_count"),
                    depth,
                    rs.getTimestamp("approved_at") == null
                            ? null
                            : rs.getTimestamp("approved_at").toInstant(),
                    rs.getInt("stages_completed"),
                    rs.getBoolean("bonus_stage_eligible"),
                    rs.getObject("item_set_id", UUID.class));
        };
    }

    /**
     * The subtree beneath {@code rootPath}, to a bounded depth.
     *
     * <p>{@code <@} is the containment operator the GIST index serves. The depth cap matters as
     * much as the index: architecture §6.4 renders two levels and expands on demand, because
     * fetching an entire downline to draw the top of it is how a hierarchy view becomes unusable
     * at scale.
     */
    public List<DistributorNode> descendantsOf(String rootPath, int maxDepth) {
        int rootDepth = rootPath.split("\\.").length;
        return jdbc.query(
                SELECT_NODE
                        + """
                        WHERE d.path <@ CAST(? AS ltree)
                          AND nlevel(d.path) <= ?
                          AND d.deleted_at IS NULL
                        ORDER BY d.path
                        """,
                mapper(rootDepth),
                rootPath,
                rootDepth + maxDepth);
    }

    /** The upline, root first. {@code @>} is containment in the other direction. */
    public List<DistributorNode> ancestorsOf(String path) {
        return jdbc.query(
                SELECT_NODE
                        + """
                        WHERE d.path @> CAST(? AS ltree)
                          AND d.path <> CAST(? AS ltree)
                        ORDER BY nlevel(d.path)
                        """,
                mapper(1),
                path,
                path);
    }

    /** Direct children only — one level down. */
    public List<DistributorNode> childrenOf(UUID parentId) {
        return jdbc.query(
                SELECT_NODE
                        + """
                        WHERE d.referred_by = ? AND d.deleted_at IS NULL
                        ORDER BY d.created_at
                        """,
                mapper(1),
                parentId);
    }

    /** Null clears it, which is what an approval with no pack chosen means. */
    public void setItemSet(UUID distributorId, UUID itemSetId) {
        jdbc.update("UPDATE distributor SET item_set_id = ? WHERE id = ?", itemSetId, distributorId);
    }

    /** Everyone with no referrer — the top of the forest. */
    public List<DistributorNode> roots() {
        return jdbc.query(
                SELECT_NODE
                        + """
                        WHERE d.referred_by IS NULL AND d.deleted_at IS NULL
                        ORDER BY d.created_at
                        """,
                mapper(1));
    }

    /**
     * The distributor record belonging to an account, live or deleted.
     *
     * <p>Deleted rows are included on purpose: the portal needs to distinguish "you were never a
     * distributor" from "your account was removed", and hiding the second would show somebody a
     * registration form for a place they already held.
     */
    public Optional<DistributorNode> findByUserId(UUID userId) {
        return jdbc
                .query(
                        SELECT_NODE
                                + """
                                WHERE d.user_id = ?
                                ORDER BY d.created_at DESC
                                LIMIT 1
                                """,
                        mapper(1),
                        userId)
                .stream()
                .findFirst();
    }

    public Optional<DistributorNode> findById(UUID id) {
        return jdbc
                .query(
                        SELECT_NODE
                                + """
                                WHERE d.id = ?
                                """,
                        mapper(1),
                        id)
                .stream()
                .findFirst();
    }

    /** Lookup by the public identifier. Only active distributors may be named as a referrer. */
    public Optional<DistributorNode> findActiveByBusinessId(String businessId) {
        return jdbc
                .query(
                        SELECT_NODE
                                + """
                                WHERE d.business_id = ?
                                  AND d.status = 'active'
                                  AND d.deleted_at IS NULL
                                """,
                        mapper(1),
                        businessId)
                .stream()
                .findFirst();
    }

    /**
     * Locks the prospective parent and returns its current child count.
     *
     * <p>This is the whole of the width-cap correctness story. Counting children without holding
     * the parent row lets two approvals both read 3, both conclude they are the fourth, and admit
     * a fifth child — the check-then-act race described in the amended architecture §4.4.
     *
     * @return empty when there is no such distributor
     */
    public Optional<Integer> lockParentAndCountChildren(UUID parentId) {
        return jdbc
                .query(
                        """
                        SELECT direct_child_count FROM distributor
                        WHERE id = ? AND deleted_at IS NULL
                        FOR UPDATE
                        """,
                        (rs, rowNum) -> rs.getInt(1),
                        parentId)
                .stream()
                .findFirst();
    }

    /** Called under the lock taken above. */
    public void incrementChildCount(UUID parentId) {
        jdbc.update(
                "UPDATE distributor SET direct_child_count = direct_child_count + 1,"
                        + " updated_at = now() WHERE id = ?",
                parentId);
    }

    /**
     * Releases a slot when a child is deleted. Floored at zero by the column's CHECK, and guarded
     * here as well so a double-delete cannot drive it negative and silently widen the cap.
     */
    public void decrementChildCount(UUID parentId) {
        jdbc.update(
                "UPDATE distributor SET direct_child_count = GREATEST(direct_child_count - 1, 0),"
                        + " updated_at = now() WHERE id = ?",
                parentId);
    }

    public String pathOf(UUID distributorId) {
        return jdbc.queryForObject(
                "SELECT path::text FROM distributor WHERE id = ?", String.class, distributorId);
    }

    /** Next segment for a materialised path. Digits only — an ltree label rejects a UUID's hyphens. */
    public long nextPathSegment() {
        Long value = jdbc.queryForObject("SELECT nextval('distributor_path_segment_seq')", Long.class);
        return value == null ? 0L : value;
    }

    /**
     * @deprecated Business IDs became positional at V24. Retained so a restore that predates it
     *     can still be reasoned about; nothing calls it.
     */
    @Deprecated
    public long nextBusinessIdSequence() {
        Long value = jdbc.queryForObject("SELECT nextval('business_id_seq')", Long.class);
        return value == null ? 0L : value;
    }

    /**
     * The Business IDs of every child ever placed under a parent, deleted ones included.
     *
     * <p>Soft-deleted children are deliberately in scope. Their seat must not be handed out again:
     * an identifier encodes a position in the tree and is quoted on printed cards, in registration
     * records and in the audit trail, so reusing 123 for a second person would make the history of
     * that number unreadable. A seat vacated by a deletion stays vacant.
     */
    public List<String> childBusinessIds(UUID referrerId) {
        return jdbc.queryForList(
                "SELECT business_id FROM distributor WHERE referred_by = ? AND business_id IS NOT NULL",
                String.class,
                referrerId);
    }

    /** The Business IDs of every root, deleted ones included. Same reasoning as above. */
    public List<String> rootBusinessIds() {
        return jdbc.queryForList(
                "SELECT business_id FROM distributor WHERE referred_by IS NULL AND business_id IS NOT NULL",
                String.class);
    }

    /** A distributor's Business ID, or null if it has not been approved yet. */
    public String businessIdOf(UUID distributorId) {
        List<String> found =
                jdbc.queryForList(
                        "SELECT business_id FROM distributor WHERE id = ?", String.class, distributorId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Sets the identity allocated at approval. Path and Business ID are assigned exactly once. */
    public void activate(UUID distributorId, String businessId, String path) {
        jdbc.update(
                """
                UPDATE distributor
                SET business_id = ?, path = CAST(? AS ltree), status = 'active',
                    approved_at = now(), updated_at = now()
                WHERE id = ?
                """,
                businessId,
                path,
                distributorId);
    }

    public UUID createPending(UUID userId, UUID referredBy) {
        return jdbc.queryForObject(
                """
                INSERT INTO distributor (user_id, referred_by, status)
                VALUES (?, ?, 'pending')
                RETURNING id
                """,
                UUID.class,
                userId,
                referredBy);
    }

    /** Soft delete. Financial records are retained; the Business ID is retired, never reissued. */
    public void softDelete(UUID distributorId) {
        jdbc.update(
                """
                UPDATE distributor
                SET status = 'deleted', deleted_at = now(), updated_at = now()
                WHERE id = ?
                """,
                distributorId);
    }
}
