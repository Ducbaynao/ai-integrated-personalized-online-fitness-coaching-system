package com.fitnesscoaching.platform.modules.exercise.application.model;

import com.fitnesscoaching.platform.modules.exercise.domain.CatalogOption;
import com.fitnesscoaching.platform.modules.exercise.domain.MuscleGroupOption;

import java.util.List;

public record ExerciseFilterMetadata(
        List<CatalogOption> categories,
        List<MuscleGroupOption> muscleGroups,
        List<CatalogOption> equipment,
        List<CatalogOption> tags,
        List<String> difficulties,
        List<String> movementPatterns
) {
    public ExerciseFilterMetadata {
        categories = categories != null ? List.copyOf(categories) : List.of();
        muscleGroups = muscleGroups != null ? List.copyOf(muscleGroups) : List.of();
        equipment = equipment != null ? List.copyOf(equipment) : List.of();
        tags = tags != null ? List.copyOf(tags) : List.of();
        difficulties = difficulties != null ? List.copyOf(difficulties) : List.of();
        movementPatterns = movementPatterns != null ? List.copyOf(movementPatterns) : List.of();
    }
}
