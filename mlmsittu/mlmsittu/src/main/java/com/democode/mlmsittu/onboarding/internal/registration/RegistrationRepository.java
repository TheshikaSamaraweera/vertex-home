package com.democode.mlmsittu.onboarding.internal.registration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Registration rows and their append-only transition log. */
@Repository
public class RegistrationRepository {

    public record RegistrationRow(
            UUID id,
            UUID userId,
            String applicantName,
            String applicantEmail,
            String referrerBusinessId,
            UUID referrerDistributorId,
            String status,
            String fullAddress,
            String bankName,
            String bankBranch,
            String bankAccountNumber,
            UUID itemSetId,
            UUID nicDocumentId,
            UUID slipDocumentId,
            UUID identityDocumentId,
            String nicLast4,
            UUID claimedBy,
            String claimedByName,
            Instant claimedAt,
            Instant submittedAt,
            UUID reviewedBy,
            Instant reviewedAt,
            String rejectionReason,
            String rejectionNote,
            Instant createdAt) {}

    private static final String SELECT =
            """
            SELECT r.id, r.user_id, u.full_name AS applicant_name, u.email AS applicant_email,
                   r.referrer_business_id, r.referrer_distributor_id, r.status,
                   r.full_address, r.bank_name, r.bank_branch, r.bank_account_number,
                   r.item_set_id, r.nic_document_id, r.slip_document_id, r.identity_document_id,
                   idoc.nic_last4,
                   r.claimed_by, reviewer.full_name AS claimed_by_name, r.claimed_at,
                   r.submitted_at, r.reviewed_by, r.reviewed_at,
                   r.rejection_reason, r.rejection_note, r.created_at
            FROM registration r
            JOIN app_user u ON u.id = r.user_id
            LEFT JOIN app_user reviewer ON reviewer.id = r.claimed_by
            LEFT JOIN identity_document idoc ON idoc.id = r.identity_document_id
            """;

    private static final RowMapper<RegistrationRow> MAPPER =
            (rs, rowNum) ->
                    new RegistrationRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getString("applicant_name"),
                            rs.getString("applicant_email"),
                            rs.getString("referrer_business_id"),
                            rs.getObject("referrer_distributor_id", UUID.class),
                            rs.getString("status"),
                            rs.getString("full_address"),
                            rs.getString("bank_name"),
                            rs.getString("bank_branch"),
                            rs.getString("bank_account_number"),
                            rs.getObject("item_set_id", UUID.class),
                            rs.getObject("nic_document_id", UUID.class),
                            rs.getObject("slip_document_id", UUID.class),
                            rs.getObject("identity_document_id", UUID.class),
                            rs.getString("nic_last4"),
                            rs.getObject("claimed_by", UUID.class),
                            rs.getString("claimed_by_name"),
                            instant(rs.getTimestamp("claimed_at")),
                            instant(rs.getTimestamp("submitted_at")),
                            rs.getObject("reviewed_by", UUID.class),
                            instant(rs.getTimestamp("reviewed_at")),
                            rs.getString("rejection_reason"),
                            rs.getString("rejection_note"),
                            instant(rs.getTimestamp("created_at")));

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private final JdbcTemplate jdbc;

    public RegistrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RegistrationRow> findById(UUID id) {
        return jdbc.query(SELECT + " WHERE r.id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * Loads for review with the row locked.
     *
     * <p>Claim, approve and reject all go through this. Without the lock two reviewers can read
     * the same unclaimed record at the same instant and both proceed — which is exactly the
     * situation claim-based locking exists to prevent (architecture §3.3).
     */
    public Optional<RegistrationRow> findForUpdate(UUID id) {
        // The lock has to be taken on `registration` alone; FOR UPDATE cannot be applied to the
        // outer joins, so the row is locked first and then re-read with its joins.
        List<UUID> locked =
                jdbc.query(
                        "SELECT id FROM registration WHERE id = ? FOR UPDATE",
                        (rs, rowNum) -> rs.getObject(1, UUID.class),
                        id);
        return locked.isEmpty() ? Optional.empty() : findById(id);
    }

    public List<RegistrationRow> findQueue(List<String> statuses) {
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        return jdbc.query(
                SELECT + " WHERE r.status IN (" + placeholders + ") ORDER BY r.submitted_at ASC",
                MAPPER,
                statuses.toArray());
    }

    public List<RegistrationRow> findByUser(UUID userId) {
        return jdbc.query(SELECT + " WHERE r.user_id = ? ORDER BY r.created_at DESC", MAPPER, userId);
    }

    public UUID create(
            UUID userId,
            String referrerBusinessId,
            UUID referrerDistributorId,
            String fullAddress,
            String bankName,
            String bankBranch,
            String bankAccountNumber,
            UUID itemSetId,
            UUID nicDocumentId,
            UUID slipDocumentId,
            UUID identityDocumentId) {

        return jdbc.queryForObject(
                """
                INSERT INTO registration
                    (user_id, referrer_business_id, referrer_distributor_id, status,
                     full_address, bank_name, bank_branch, bank_account_number, item_set_id,
                     nic_document_id, slip_document_id, identity_document_id, submitted_at)
                VALUES (?, ?, ?, 'submitted', ?, ?, ?, ?, ?, ?, ?, ?, now())
                RETURNING id
                """,
                UUID.class,
                userId,
                referrerBusinessId,
                referrerDistributorId,
                fullAddress,
                bankName,
                bankBranch,
                bankAccountNumber,
                itemSetId,
                nicDocumentId,
                slipDocumentId,
                identityDocumentId);
    }

    public void claim(UUID registrationId, UUID reviewerId) {
        jdbc.update(
                """
                UPDATE registration
                SET claimed_by = ?, claimed_at = now(), status = 'under_review', updated_at = now()
                WHERE id = ?
                """,
                reviewerId,
                registrationId);
    }

    public void release(UUID registrationId) {
        jdbc.update(
                """
                UPDATE registration
                SET claimed_by = NULL, claimed_at = NULL, status = 'submitted', updated_at = now()
                WHERE id = ?
                """,
                registrationId);
    }

    public void markReviewed(
            UUID registrationId, String status, UUID reviewerId, String reason, String note) {
        jdbc.update(
                """
                UPDATE registration
                SET status = ?, reviewed_by = ?, reviewed_at = now(),
                    rejection_reason = ?, rejection_note = ?, updated_at = now()
                WHERE id = ?
                """,
                status,
                reviewerId,
                reason,
                note,
                registrationId);
    }

    /** Append-only. Every transition, with who caused it and why (architecture §3.3). */
    public void recordEvent(
            UUID registrationId, String fromStatus, String toStatus, UUID actorId, String reason,
            String note) {
        jdbc.update(
                """
                INSERT INTO registration_event
                    (registration_id, from_status, to_status, actor_id, reason, note)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                registrationId,
                fromStatus,
                toStatus,
                actorId,
                reason,
                note);
    }

    public record EventRow(
            String fromStatus, String toStatus, String actorName, String reason, String note,
            Instant createdAt) {}

    public List<EventRow> timelineOf(UUID registrationId) {
        return jdbc.query(
                """
                SELECT e.from_status, e.to_status, a.full_name AS actor_name, e.reason, e.note,
                       e.created_at
                FROM registration_event e
                LEFT JOIN app_user a ON a.id = e.actor_id
                WHERE e.registration_id = ?
                ORDER BY e.created_at
                """,
                (rs, rowNum) ->
                        new EventRow(
                                rs.getString("from_status"),
                                rs.getString("to_status"),
                                rs.getString("actor_name"),
                                rs.getString("reason"),
                                rs.getString("note"),
                                instant(rs.getTimestamp("created_at"))),
                registrationId);
    }
}
