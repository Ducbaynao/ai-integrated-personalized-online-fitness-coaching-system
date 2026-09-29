package com.fitnesscoaching.platform.modules.exercise.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminExerciseSummary(
        UUID id,
        String code,
        String name,
        String categoryCode,
        String difficulty,
        String movementPattern,
        ExerciseLifecycleStatus status,
        long version,
        UUID canonicalReplacementId,
        int variationCount,
        Instant updatedAt
) {
}
