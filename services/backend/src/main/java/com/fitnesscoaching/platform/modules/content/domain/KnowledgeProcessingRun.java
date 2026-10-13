package com.fitnesscoaching.platform.modules.content.domain;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeProcessingRun(
        UUID id,
        UUID knowledgeVersionId,
        String runType,
        String status,
        UUID modelVersionId,
        String statisticsJson,
        String errorMessage,
        String operationKey,
        UUID claimToken,
        Instant lockedAt,
        String lockedBy,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant leaseExpiresAt,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
}
