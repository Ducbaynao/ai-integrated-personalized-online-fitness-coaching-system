package com.fitnesscoaching.platform.modules.exercise.domain;

import java.util.UUID;

public record ExerciseGuidance(
        UUID id,
        UUID exerciseVariationId,
        String type,
        String title,
        String description,
        String correction,
        String severity,
        int sortOrder
) {
}
