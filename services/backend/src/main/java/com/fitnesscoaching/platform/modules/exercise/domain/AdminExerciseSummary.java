package com.fitnesscoaching.platform.modules.exercise.domain;

import java.time.Instant;
import java.util.List;
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
        List<String> muscleGroupCodes,
        List<String> equipmentCodes,
        boolean mediaAvailable,
        int variationCount,
        Instant updatedAt
) {
    public AdminExerciseSummary {
        muscleGroupCodes = muscleGroupCodes == null ? List.of() : List.copyOf(muscleGroupCodes);
        equipmentCodes = equipmentCodes == null ? List.of() : List.copyOf(equipmentCodes);
    }
}
