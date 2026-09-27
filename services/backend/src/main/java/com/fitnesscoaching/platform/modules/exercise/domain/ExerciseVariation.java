package com.fitnesscoaching.platform.modules.exercise.domain;

import java.util.List;
import java.util.UUID;

public record ExerciseVariation(
        UUID id,
        String code,
        String name,
        String description,
        String instructions,
        String difficulty,
        boolean defaultVariation,
        List<ExerciseMuscle> muscles,
        List<ExerciseEquipment> equipment,
        List<ExerciseMediaReference> media
) {
    public ExerciseVariation {
        muscles = muscles != null ? List.copyOf(muscles) : List.of();
        equipment = equipment != null ? List.copyOf(equipment) : List.of();
        media = media != null ? List.copyOf(media) : List.of();
    }
}
