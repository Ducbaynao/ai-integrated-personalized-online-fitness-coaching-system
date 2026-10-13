package com.fitnesscoaching.platform.modules.content.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity representing an expert/specialist review event on a knowledge version.
 */
public record KnowledgeReviewRecord(
        UUID id,
        UUID knowledgeVersionId,
        UUID reviewerId,
        String reviewerIdentity,
        String reviewerRole,
        ReviewDecision decision,
        String contentHash,
        String notes,
        Instant reviewedAt
) {
    public KnowledgeReviewRecord(
            UUID id,
            UUID knowledgeVersionId,
            UUID reviewerId,
            ReviewDecision decision,
            String notes,
            Instant reviewedAt
    ) {
        this(id, knowledgeVersionId, reviewerId, null, null, decision, null, notes, reviewedAt);
    }
}
