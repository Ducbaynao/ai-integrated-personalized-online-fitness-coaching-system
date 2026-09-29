package com.fitnesscoaching.platform.modules.exercise.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminExercise(
        UUID id,
        String code,
        String name,
        String categoryCode,
        String description,
        String instructions,
        String difficulty,
        String movementPattern,
        boolean unilateral,
        ExerciseLifecycleStatus status,
        long version,
        UUID canonicalReplacementId,
        List<String> tagCodes,
        List<AdminExerciseVariation> variations,
        Instant createdAt,
        Instant updatedAt
) {
    public AdminExercise {
        tagCodes = tagCodes == null ? List.of() : List.copyOf(tagCodes);
        variations = variations == null ? List.of() : List.copyOf(variations);
    }
}
