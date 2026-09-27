package com.fitnesscoaching.platform.modules.exercise.domain;

import java.util.List;
import java.util.UUID;

public record ExerciseCatalogItem(
        UUID id,
        String code,
        String name,
        CatalogOption category,
        String description,
        String difficulty,
        String movementPattern,
        boolean unilateral,
        List<CatalogOption> primaryMuscles,
        List<CatalogOption> equipment,
        List<CatalogOption> tags,
        int variationCount,
        boolean mediaAvailable
) {
    public ExerciseCatalogItem {
        primaryMuscles = primaryMuscles != null ? List.copyOf(primaryMuscles) : List.of();
        equipment = equipment != null ? List.copyOf(equipment) : List.of();
        tags = tags != null ? List.copyOf(tags) : List.of();
    }
}
