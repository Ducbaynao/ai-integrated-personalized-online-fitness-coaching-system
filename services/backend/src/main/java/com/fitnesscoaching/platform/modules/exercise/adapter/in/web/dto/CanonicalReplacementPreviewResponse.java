package com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.exercise.application.model.CanonicalReplacementExercise;
import com.fitnesscoaching.platform.modules.exercise.application.model.CanonicalReplacementPreview;

import java.util.UUID;

public record CanonicalReplacementPreviewResponse(
        ExerciseReferenceResponse sourceExercise,
        long expectedVersion,
        ExerciseReferenceResponse currentTarget,
        UsageImpactResponse usageImpact
) {
    public static CanonicalReplacementPreviewResponse fromDomain(CanonicalReplacementPreview preview) {
        return new CanonicalReplacementPreviewResponse(
                ExerciseReferenceResponse.fromDomain(preview.sourceExercise()),
                preview.expectedVersion(),
                preview.currentTarget() == null ? null : ExerciseReferenceResponse.fromDomain(preview.currentTarget()),
                new UsageImpactResponse(preview.usageImpact().availability(), preview.usageImpact().count()));
    }

    public record ExerciseReferenceResponse(UUID id, String code, String name, String status) {
        private static ExerciseReferenceResponse fromDomain(CanonicalReplacementExercise exercise) {
            return new ExerciseReferenceResponse(
                    exercise.id(), exercise.code(), exercise.name(), exercise.status().name());
        }
    }

    public record UsageImpactResponse(String availability, Long count) {
    }
}
