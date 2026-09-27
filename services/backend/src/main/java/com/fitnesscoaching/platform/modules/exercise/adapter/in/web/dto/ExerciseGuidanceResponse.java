package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseGuidance;

import java.util.UUID;

public record ExerciseGuidanceResponse(
        UUID id,
        UUID exerciseVariationId,
        String type,
        String title,
        String description,
        String correction,
        String severity,
        int sortOrder
) {
    public static ExerciseGuidanceResponse fromDomain(ExerciseGuidance guidance) {
        return new ExerciseGuidanceResponse(
                guidance.id(),
                guidance.exerciseVariationId(),
                guidance.type(),
                guidance.title(),
                guidance.description(),
                guidance.correction(),
                guidance.severity(),
                guidance.sortOrder()
        );
    }
}
