package com.democode.mlmsittu.hierarchy.internal;

import com.democode.mlmsittu.hierarchy.api.StageProgress;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tracks the four enrolment stages of §0.2.
 *
 * <p>Every method here <b>must be called while the distributor's row is already locked</b>. That is
 * not a suggestion: {@code DistributorService.attachToReferrer} takes
 * {@code SELECT … FOR UPDATE} on the parent before counting children, and stage progression rides
 * on that same lock. Two approvals under one referrer are therefore serialised, and each sees the
 * stage count the other committed.
 *
 * <p>Getting that wrong is the failure the amended architecture §4.4 describes: both approvals read
 * "3 stages complete", both award the fourth, and the distributor collects the bonus twice. Nothing
 * about it is visible afterwards — the totals simply do not add up, months later, in a dispute.
 *
 * <h2>What is deliberately not here</h2>
 *
 * No reward is granted. Progression is fully specified in §0.2; <em>what a completed stage is
 * worth</em> is not specified anywhere, and five questions about it are still open with the client.
 * {@link StageRewardPolicy} is the seam where that lands.
 */
@Service
public class StageProgressionService {

    private static final Logger log = LoggerFactory.getLogger(StageProgressionService.class);

    private final JdbcTemplate jdbc;
    private final StageRewardPolicy rewardPolicy;

    public StageProgressionService(JdbcTemplate jdbc, StageRewardPolicy rewardPolicy) {
        this.jdbc = jdbc;
        this.rewardPolicy = rewardPolicy;
    }

    /**
     * Records that {@code parentId} gained a referral, completing one stage.
     *
     * <p>Capped at {@link StageProgress#TOTAL_STAGES}. The width cap should make an extra referral
     * impossible anyway, but capping here as well means raising the width cap alone cannot
     * silently invent a stage nobody defined — which matters now that both numbers have moved
     * once and will likely move again.
     */
    void recordReferralGained(UUID parentId, UUID childDistributorId) {
        ensureRow(parentId);

        Integer completed =
                jdbc.queryForObject(
                        """
                        UPDATE referral_stage_progress
                        SET stages_completed = LEAST(stages_completed + 1, %1$d),
                            bonus_stage_eligible = (LEAST(stages_completed + 1, %1$d) >= %1$d),
                            all_stages_completed_at =
                                CASE WHEN LEAST(stages_completed + 1, %1$d) >= %1$d
                                          AND all_stages_completed_at IS NULL
                                     THEN now() ELSE all_stages_completed_at END,
                            updated_at = now()
                        WHERE distributor_id = ?
                        RETURNING stages_completed
                        """
                                .formatted(StageProgress.TOTAL_STAGES),
                        Integer.class,
                        parentId);

        if (completed == null) {
            return;
        }

        jdbc.update(
                """
                INSERT INTO referral_stage_event
                    (distributor_id, stage_number, event_type, triggered_by_distributor_id)
                VALUES (?, ?, 'unlocked', ?)
                """,
                parentId,
                completed,
                childDistributorId);

        rewardPolicy.onStageUnlocked(parentId, completed);

        if (completed >= StageProgress.TOTAL_STAGES) {
            log.info("Distributor {} completed all four stages and is bonus-eligible", parentId);
            // Read here rather than in the rewards module, so that module never needs to know the
            // shape of the distributor table — and so the read happens under the lock this
            // transaction already holds on that row.
            rewardPolicy.onBonusStageUnlocked(parentId, chosenPackOf(parentId));
        }
    }

    /** The pack chosen at registration and copied onto the distributor at approval. */
    private UUID chosenPackOf(UUID distributorId) {
        return jdbc.queryForObject(
                "SELECT item_set_id FROM distributor WHERE id = ?", UUID.class, distributorId);
    }

    /**
     * Reverses a stage when a referral is removed.
     *
     * <p>An assumption, stated plainly because it is not in §0.2: a deleted child releases both the
     * referral slot and the stage it completed. The alternative — keeping a stage earned by an
     * account that no longer exists — makes deletion a way to keep a reward while freeing the slot
     * to earn it again. <b>Confirm this with the client before any reward has value.</b>
     */
    void recordReferralLost(UUID parentId, UUID childDistributorId) {
        ensureRow(parentId);

        Integer completed =
                jdbc.queryForObject(
                        """
                        UPDATE referral_stage_progress
                        SET stages_completed = GREATEST(stages_completed - 1, 0),
                            bonus_stage_eligible = (GREATEST(stages_completed - 1, 0) >= %1$d),
                            all_stages_completed_at =
                                CASE WHEN GREATEST(stages_completed - 1, 0) < %1$d
                                     THEN NULL ELSE all_stages_completed_at END,
                            updated_at = now()
                        WHERE distributor_id = ?
                        RETURNING stages_completed
                        """
                                .formatted(StageProgress.TOTAL_STAGES),
                        Integer.class,
                        parentId);

        if (completed == null) {
            return;
        }

        jdbc.update(
                """
                INSERT INTO referral_stage_event
                    (distributor_id, stage_number, event_type, triggered_by_distributor_id)
                VALUES (?, ?, 'revoked', ?)
                """,
                parentId,
                completed + 1,
                childDistributorId);

        rewardPolicy.onStageRevoked(parentId, completed + 1);
    }

    @Transactional(readOnly = true)
    public Optional<StageProgress> progressOf(UUID distributorId) {
        return jdbc
                .query(
                        """
                        SELECT distributor_id, stages_completed, bonus_stage_eligible,
                               all_stages_completed_at
                        FROM referral_stage_progress WHERE distributor_id = ?
                        """,
                        (rs, rowNum) ->
                                new StageProgress(
                                        rs.getObject("distributor_id", UUID.class),
                                        rs.getInt("stages_completed"),
                                        StageProgress.TOTAL_STAGES,
                                        rs.getBoolean("bonus_stage_eligible"),
                                        rs.getTimestamp("all_stages_completed_at") == null
                                                ? null
                                                : rs.getTimestamp("all_stages_completed_at")
                                                        .toInstant()),
                        distributorId)
                .stream()
                .findFirst();
    }

    /** Starts a newly activated distributor at zero stages. */
    void initialise(UUID distributorId) {
        ensureRow(distributorId);
    }

    private void ensureRow(UUID distributorId) {
        jdbc.update(
                """
                INSERT INTO referral_stage_progress (distributor_id) VALUES (?)
                ON CONFLICT (distributor_id) DO NOTHING
                """,
                distributorId);
    }
}
