package com.democode.mlmsittu.hierarchy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * How far a distributor has got through the four enrolment stages of §0.2.
 *
 * @param stagesCompleted 0–4, one per referral brought in
 * @param bonusStageEligible automatic on completing all four. What unlocking the bonus then
 *     requires or yields is still undefined — see {@code StageRewardPolicy}.
 */
public record StageProgress(
        UUID distributorId,
        int stagesCompleted,
        int totalStages,
        boolean bonusStageEligible,
        Instant allStagesCompletedAt) {

    /**
     * Raised from four on 28 Aug 2026 at the client's request.
     *
     * <p><b>This is the only place the number lives in Java.</b> The SQL that increments and
     * decrements a stage interpolates it rather than repeating a literal, because when it was a
     * literal it appeared six times across two methods and there was no way to change it without
     * finding all six.
     *
     * <p>The referral width cap moves with it — {@code referral.max_direct} in {@code
     * system_config} — since a stage is earned per referral and a cap below the stage count makes
     * the last stage unreachable.
     */
    public static final int TOTAL_STAGES = 5;

    public int stagesRemaining() {
        return Math.max(TOTAL_STAGES - stagesCompleted, 0);
    }
}
