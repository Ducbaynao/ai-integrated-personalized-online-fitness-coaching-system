package com.fitnesscoaching.platform.modules.workout.domain;

import java.time.Instant;
import java.util.UUID;

public record WorkoutSessionAdjustment(
        UUID id, UUID plannedWorkoutId, Type type, UUID plannedSessionExerciseId,
        UUID replacementExerciseVariationId, String beforeValue, String afterValue,
        String reason, UUID adjustedBy, Instant createdAt
) {
    public enum Type { EXERCISE_SWAP, LOAD, REPS, SETS, DURATION, ORDER, NOTE, OTHER }
    public WorkoutSessionAdjustment {
        if (plannedWorkoutId == null || type == null || afterValue == null || reason == null || reason.isBlank())
            throw new IllegalArgumentException("Invalid occurrence adjustment");
        boolean swapComplete = plannedSessionExerciseId != null && replacementExerciseVariationId != null;
        if ((type == Type.EXERCISE_SWAP) != swapComplete)
            throw new IllegalArgumentException("Exercise swap requires original prescription and replacement variation");
    }
}
