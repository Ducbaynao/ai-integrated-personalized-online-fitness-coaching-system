package com.fitnesscoaching.platform.modules.ai.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity for context snapshot stored in fitness.ai_context_snapshots.
 */
public record AiContextSnapshot(
        UUID id,
        UUID aiRunId,
        String contextVersion,
        String contextPayload,
        String dataAvailability,
        String dataQualitySummary,
        String trainingContinuitySummary,
        String contextHash,
        Instant createdAt
) {
}
