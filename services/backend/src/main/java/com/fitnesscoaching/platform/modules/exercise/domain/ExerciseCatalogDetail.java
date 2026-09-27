package com.fitnesscoaching.platform.modules.exercise.domain;

import java.util.List;
import java.util.UUID;

public record ExerciseCatalogDetail(
        UUID id,
        String code,
        String name,
        CatalogOption category,
        String description,
        String instructions,
        String difficulty,
        String movementPattern,
        boolean unilateral,
        List<ExerciseVariation> variations,
        List<CatalogOption> tags,
        List<ExerciseGuidance> guidance,
        boolean mediaAvailable
) {
    public ExerciseCatalogDetail {
        variations = variations != null ? List.copyOf(variations) : List.of();
        tags = tags != null ? List.copyOf(tags) : List.of();
        guidance = guidance != null ? List.copyOf(guidance) : List.of();
    }
}
