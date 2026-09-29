package com.fitnesscoaching.platform.modules.exercise.application.port.in;

import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseVariation;

import java.util.List;

public record ExerciseDraftData(
        String code,
        String name,
        String categoryCode,
        String description,
        String instructions,
        String difficulty,
        String movementPattern,
        boolean unilateral,
        List<String> tagCodes,
        List<AdminExerciseVariation> variations
) {
    public ExerciseDraftData {
        tagCodes = tagCodes == null ? List.of() : List.copyOf(tagCodes);
        variations = variations == null ? List.of() : List.copyOf(variations);
    }
}
