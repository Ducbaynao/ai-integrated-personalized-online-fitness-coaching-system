package com.fitnesscoaching.platform.modules.exercise.application.port.in;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseLifecycleStatus;

import java.util.UUID;

public record AdminExerciseQuery(
        UUID adminUserId,
        String query,
        ExerciseLifecycleStatus status,
        int page,
        int size
) {
}
