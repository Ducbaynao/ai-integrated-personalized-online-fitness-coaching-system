package com.fitnesscoaching.platform.modules.exercise.application.model;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseLifecycleStatus;

import java.util.UUID;

public record CanonicalReplacementExercise(
        UUID id,
        String code,
        String name,
        ExerciseLifecycleStatus status
) {
}
