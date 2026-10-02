package com.fitnesscoaching.platform.modules.exercise.application.model;

public record CanonicalReplacementPreview(
        CanonicalReplacementExercise sourceExercise,
        long expectedVersion,
        CanonicalReplacementExercise currentTarget,
        UsageImpact usageImpact
) {
    public record UsageImpact(String availability, Long count) {
        public static UsageImpact notAvailable() {
            return new UsageImpact("NOT_AVAILABLE", null);
        }
    }
}
