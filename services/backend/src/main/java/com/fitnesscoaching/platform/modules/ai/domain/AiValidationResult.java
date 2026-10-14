package com.fitnesscoaching.platform.modules.ai.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity for validation results stored in fitness.ai_validation_results.
 */
public record AiValidationResult(
        UUID id,
        UUID aiRunId,
        String validatorCode,
        boolean passed,
        String severity,
        String details,
        Instant createdAt
) {
}
