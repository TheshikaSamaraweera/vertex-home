package com.democode.mlmsittu.hierarchy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One node of the referral tree.
 *
 * @param path the internal materialised path. Present for admin views (architecture §2.1 still
 *     displays it there) but never the public identifier — it is mutable under tree edits, leaks
 *     the entire upline, is trivially enumerable, and carries no error detection.
 * @param depth levels below the root of the query, so a caller can indent without re-parsing paths
 */
public record DistributorNode(
        UUID id,
        String businessId,
        UUID userId,
        String fullName,
        UUID referredBy,
        String path,
        String status,
        int directChildCount,
        int depth,
        Instant approvedAt,
        int stagesCompleted,
        boolean bonusEligible,
        UUID itemSetId) {}
