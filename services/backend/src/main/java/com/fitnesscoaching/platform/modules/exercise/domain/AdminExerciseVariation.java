package com.fitnesscoaching.platform.modules.exercise.domain;

import java.util.List;
import java.util.UUID;

public record AdminExerciseVariation(
        UUID id,
        String code,
        String name,
        String description,
        String instructions,
        String difficulty,
        boolean defaultVariation,
        boolean active,
        List<AdminExerciseMuscle> muscles,
        List<AdminExerciseEquipment> equipment
) {
    public AdminExerciseVariation {
        muscles = muscles == null ? List.of() : List.copyOf(muscles);
        equipment = equipment == null ? List.of() : List.copyOf(equipment);
    }
}
