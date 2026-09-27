package com.democode.mlmsittu.hierarchy.internal;

import com.democode.mlmsittu.shared.audit.api.AuditContext;
import com.democode.mlmsittu.shared.audit.api.Audited;
import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marketing officers: who they look after, and what that has earned them.
 *
 * <p>The earnings here are <b>informational</b>. Nothing records a payment, tracks a balance, or
 * marks anything settled — the officer's portal says "you earned 1% from this customer and this
 * pack" and the cost analysis shows the same figure as a cost. Settling up is the client's
 * accounting, and inventing half a ledger would be worse than leaving it out.
 *
 * <p>Which is why every figure on this screen is computed on read. There is no stored balance to
 * drift, nothing to reconcile, and changing an officer's rate changes what their screen says
 * immediately — including for packs already issued, because the number was never a debt, only a
 * statement about a percentage.
 */
@Service
public class MarketingOfficerService {

    /** One per cent, as a fraction. Matches the column default in V32. */
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("0.0100");

    private final JdbcTemplate jdbc;

    public MarketingOfficerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ shapes

    public record Officer(
            UUID userId,
            String fullName,
            String email,
            String mobile,
            UUID profilePhotoId,
            BigDecimal commissionRate,
            int customerCount,
            BigDecimal earned) {}

    /** One customer an officer looks after, and where they have got to. */
    public record AssignedCustomer(
            UUID distributorId,
            String businessId,
            String fullName,
            String status,
            int stagesCompleted,
            int totalStages,
            Instant approvedAt,
            Instant expiresAt,
            String packName,
            BigDecimal packPrice,
            boolean packIssued,
            Instant packIssuedAt,
            BigDecimal earned) {}

    // ------------------------------------------------------------------ administration

    /** Every officer, with how many customers they carry and what that has earned. */
    @Transactional(readOnly = true)
    public List<Officer> list() {
        return jdbc.query(
                """
                SELECT u.id, u.full_name, u.email, u.mobile, u.profile_photo_id,
                       mo.commission_rate,
                       (SELECT count(*) FROM distributor d
                         WHERE d.marketing_officer_id = u.id AND d.deleted_at IS NULL) AS customers,
                       COALESCE((
                           SELECT SUM(s.set_price * mo.commission_rate)
                             FROM reward_entitlement e
                             JOIN distributor d2 ON d2.id = e.distributor_id
                             JOIN item_set s     ON s.id = e.item_set_id
                            WHERE d2.marketing_officer_id = u.id AND e.status = 'issued'
                       ), 0) AS earned
                  FROM marketing_officer mo
                  JOIN app_user u ON u.id = mo.user_id
                 ORDER BY u.full_name
                """,
                (rs, row) ->
                        new Officer(
                                rs.getObject("id", UUID.class),
                                rs.getString("full_name"),
                                rs.getString("email"),
                                rs.getString("mobile"),
                                rs.getObject("profile_photo_id", UUID.class),
                                rs.getBigDecimal("commission_rate"),
                                rs.getInt("customers"),
                                rs.getBigDecimal("earned").setScale(2, java.math.RoundingMode.HALF_UP)));
    }

    @Transactional(readOnly = true)
    public Officer get(UUID userId) {
        return list().stream()
                .filter(officer -> officer.userId().equals(userId))
                .findFirst()
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "OFFICER_NOT_FOUND", "That marketing officer does not exist."));
    }

    /**
     * Makes an existing account a marketing officer.
     *
     * <p>Separate from creating the account. An officer is an ordinary user with a role and a row
     * here, so the same account-creation path serves them as serves everybody else — one place
     * where a password is set, one place where an identifier is checked.
     */
    @Transactional
    @Audited(action = "MARKETING_OFFICER_CREATED", entityType = "app_user", auditFailures = true)
    public void enrol(UUID userId, BigDecimal rate, UUID actorId) {
        BigDecimal effective = rate == null ? DEFAULT_RATE : validated(rate);

        jdbc.update(
                """
                INSERT INTO marketing_officer (user_id, commission_rate, updated_by)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                    SET commission_rate = EXCLUDED.commission_rate,
                        updated_by = EXCLUDED.updated_by,
                        updated_at = now()
                """,
                userId,
                effective,
                actorId);

        jdbc.update(
                """
                INSERT INTO user_role (user_id, role_id)
                SELECT ?, id FROM app_role WHERE code = 'MARKETING_OFFICER'
                ON CONFLICT DO NOTHING
                """,
                userId);

        AuditContext.record(userId, null, Map.of("commissionRate", effective.toPlainString()));
    }

    /** Changes one officer's rate. */
    @Transactional
    @Audited(action = "MARKETING_OFFICER_RATE_CHANGED", entityType = "app_user", auditFailures = true)
    public Officer setRate(UUID userId, BigDecimal rate, UUID actorId) {
        BigDecimal effective = validated(rate);

        int updated =
                jdbc.update(
                        """
                        UPDATE marketing_officer
                           SET commission_rate = ?, updated_by = ?, updated_at = now()
                         WHERE user_id = ?
                        """,
                        effective,
                        actorId,
                        userId);

        if (updated == 0) {
            throw new NotFoundException(
                    "OFFICER_NOT_FOUND", "That marketing officer does not exist.");
        }

        AuditContext.record(userId, null, Map.of("commissionRate", effective.toPlainString()));
        return get(userId);
    }

    /**
     * Puts a customer under an officer, or takes them out from under one.
     *
     * <p>{@code officerUserId} null clears the assignment, which is what happens when an officer
     * leaves and their customers have not been given to anybody yet.
     */
    @Transactional
    @Audited(action = "MARKETING_OFFICER_ASSIGNED", entityType = "distributor", auditFailures = true)
    public void assign(UUID distributorId, UUID officerUserId) {
        if (officerUserId != null) {
            Integer isOfficer =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM marketing_officer WHERE user_id = ?",
                            Integer.class,
                            officerUserId);
            if (isOfficer == null || isOfficer == 0) {
                // Guarded because the column is a plain reference to app_user: without this an
                // administrator could assign a customer to another customer, and the officer's
                // portal would then be the only thing that noticed.
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "NOT_A_MARKETING_OFFICER",
                        "That account is not a marketing officer.");
            }
        }

        int updated =
                jdbc.update(
                        "UPDATE distributor SET marketing_officer_id = ?, updated_at = now() WHERE id = ?",
                        officerUserId,
                        distributorId);

        if (updated == 0) {
            throw new NotFoundException("DISTRIBUTOR_NOT_FOUND", "No such customer.");
        }

        AuditContext.record(
                distributorId, null, Map.of("marketingOfficerId", String.valueOf(officerUserId)));
    }

    // ------------------------------------------------------------------ the officer's own view

    /**
     * The customers one officer looks after, and where each has got to.
     *
     * <p>Scoped to the officer asking. There is no shape of this call that returns somebody else's
     * customers — the id comes from the session, never from a parameter.
     */
    @Transactional(readOnly = true)
    public List<AssignedCustomer> customersOf(UUID officerUserId) {
        return jdbc.query(
                """
                SELECT d.id                AS distributor_id,
                       d.business_id,
                       u.full_name,
                       d.status,
                       COALESCE(p.stages_completed, 0) AS stages_completed,
                       d.approved_at,
                       d.expires_at,
                       s.name              AS pack_name,
                       s.set_price         AS pack_price,
                       e.status = 'issued' AS pack_issued,
                       e.issued_at,
                       COALESCE(mo.commission_rate, 0) AS commission_rate
                  FROM distributor d
                  JOIN app_user u  ON u.id = d.user_id
                  JOIN marketing_officer mo ON mo.user_id = d.marketing_officer_id
                  LEFT JOIN referral_stage_progress p ON p.distributor_id = d.id
                  LEFT JOIN item_set s ON s.id = d.item_set_id
                  LEFT JOIN reward_entitlement e ON e.distributor_id = d.id
                 WHERE d.marketing_officer_id = ?
                   AND d.deleted_at IS NULL
                 ORDER BY d.created_at DESC
                """,
                (rs, row) -> {
                    BigDecimal price = rs.getBigDecimal("pack_price");
                    BigDecimal rate = rs.getBigDecimal("commission_rate");
                    boolean issued = rs.getBoolean("pack_issued");

                    // Earned only once the pack is actually in the customer's hands. A pack
                    // somebody is merely eligible for has earned nobody anything yet.
                    BigDecimal earned =
                            issued && price != null
                                    ? price.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP)
                                    : BigDecimal.ZERO;

                    return new AssignedCustomer(
                            rs.getObject("distributor_id", UUID.class),
                            rs.getString("business_id"),
                            rs.getString("full_name"),
                            rs.getString("status"),
                            rs.getInt("stages_completed"),
                            com.democode.mlmsittu.shared.businessid.PositionalId.SEATS,
                            instant(rs.getTimestamp("approved_at")),
                            instant(rs.getTimestamp("expires_at")),
                            rs.getString("pack_name"),
                            price,
                            issued,
                            instant(rs.getTimestamp("issued_at")),
                            earned);
                },
                officerUserId);
    }

    /** The officer's own record, for the header of their portal. */
    @Transactional(readOnly = true)
    public Officer me(UUID officerUserId) {
        return get(officerUserId);
    }

    private static BigDecimal validated(BigDecimal rate) {
        if (rate == null
                || rate.compareTo(BigDecimal.ZERO) < 0
                || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_COMMISSION_RATE",
                    "A rate is between 0 and 1 — 0.01 is one per cent.");
        }
        return rate.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
