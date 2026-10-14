package com.fitnesscoaching.platform.modules.ai.domain;

import java.util.UUID;

/**
 * Domain entity for input references stored in fitness.ai_run_input_references.
 */
public record AiRunInputReference(
        UUID aiRunId,
        String entityType,
        UUID entityId,
        String referenceRole
) {
}
