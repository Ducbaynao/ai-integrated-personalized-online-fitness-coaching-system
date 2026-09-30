package com.fitnesscoaching.platform.modules.exercise.application.model;

import com.fitnesscoaching.platform.modules.exercise.domain.CatalogOption;
import com.fitnesscoaching.platform.modules.exercise.domain.MuscleGroupOption;

import java.util.List;

public record AdminExerciseFormMetadata(
        List<CatalogOption> categories,
        List<MuscleGroupOption> muscleGroups,
        List<CatalogOption> equipment,
        List<CatalogOption> tags,
        List<String> difficulties,
        List<CatalogOption> movementPatterns
) {
    public AdminExerciseFormMetadata {
        categories = copy(categories);
        muscleGroups = muscleGroups != null ? List.copyOf(muscleGroups) : List.of();
        equipment = copy(equipment);
        tags = copy(tags);
        difficulties = difficulties != null ? List.copyOf(difficulties) : List.of();
        movementPatterns = copy(movementPatterns);
    }

    private static List<CatalogOption> copy(List<CatalogOption> values) {
        return values != null ? List.copyOf(values) : List.of();
    }
}
